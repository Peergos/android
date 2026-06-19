package peergos.android;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import peergos.server.simulation.FileAsyncReader;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.MimeTypes;
import peergos.shared.user.fs.Thumbnail;
import peergos.shared.util.PathUtil;

public class PeergosDocumentsProvider extends DocumentsProvider {

    private static final String TAG = "PeergosDocsProvider";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
    };

    private final ExecutorService streamingPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PeergosDocsProvider stream");
        t.setDaemon(true);
        return t;
    });

    private volatile UploadProgressNotifier progressNotifier;

    private UploadProgressNotifier progressNotifier() {
        UploadProgressNotifier n = progressNotifier;
        if (n == null) {
            synchronized (this) {
                n = progressNotifier;
                if (n == null) n = progressNotifier = new UploadProgressNotifier(getContext());
            }
        }
        return n;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        Optional<UserContext> ctxOpt = PeergosSession.context();
        if (ctxOpt.isEmpty()) return cursor;
        UserContext ctx = ctxOpt.get();
        String username = ctx.username;

        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Root.COLUMN_ROOT_ID, username);
        row.add(Root.COLUMN_DOCUMENT_ID, "/" + username);
        row.add(Root.COLUMN_TITLE, "Peergos");
        row.add(Root.COLUMN_SUMMARY, username);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_CREATE);
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher_round);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        FileWrapper fw = lookupOrThrow(documentId);
        int slash = documentId.lastIndexOf('/');
        boolean parentWritable = false;
        if (slash > 0) {
            Optional<FileWrapper> parent = sessionOrThrow().context.getByPath(documentId.substring(0, slash)).join();
            parentWritable = parent.isPresent() && parent.get().isWritable();
        }
        addRow(cursor, fw, documentId, parentWritable);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        Session s = sessionOrThrow();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        FileWrapper parent = lookupOrThrow(parentDocumentId);
        boolean parentWritable = parent.isWritable();
        Set<FileWrapper> children = parent.getChildren(s.crypto.hasher, s.network).join();
        for (FileWrapper child : children) {
            if (child.getFileProperties().isHidden) continue;
            String childId = joinPath(parentDocumentId, child.getName());
            addRow(cursor, child, childId, parentWritable);
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        boolean wantsRead = mode.indexOf('r') >= 0;
        boolean wantsWrite = mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0;
        if (wantsRead && wantsWrite)
            throw new UnsupportedOperationException("rw mode not supported");
        if (wantsWrite)
            return openForWrite(documentId, signal);
        return openForRead(documentId, signal);
    }

    private ParcelFileDescriptor openForRead(String documentId, CancellationSignal signal)
            throws FileNotFoundException {
        // Hand back a seekable FD via StorageManager.openProxyFileDescriptor so image and
        // video viewers (BitmapFactory header peek, MP4 moov-atom lookup) can lseek. A
        // pipe is non-seekable and silently breaks both — that's the blank-screen path.
        Session s = sessionOrThrow();
        FileWrapper fw = lookupOrThrow(documentId);
        long size = fw.getSize();
        StorageManager sm = (StorageManager) getContext().getSystemService(Context.STORAGE_SERVICE);
        HandlerThread thread = new HandlerThread("PeergosProxyFd-" + Integer.toHexString(documentId.hashCode()));
        thread.start();
        try {
            return sm.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    new PeergosProxyCallback(fw, size, s, thread),
                    new Handler(thread.getLooper()));
        } catch (IOException e) {
            thread.quitSafely();
            throw rethrowAsFnf("openProxyFileDescriptor " + documentId, e);
        }
    }

    private ParcelFileDescriptor openForWrite(String documentId, CancellationSignal signal)
            throws FileNotFoundException {
        Session s = sessionOrThrow();
        int slash = documentId.lastIndexOf('/');
        if (slash <= 0) throw new FileNotFoundException("Cannot write to root: " + documentId);
        String parentId = documentId.substring(0, slash);
        String name = documentId.substring(slash + 1);
        FileWrapper parent = lookupOrThrow(parentId);
        if (!parent.isWritable()) throw new FileNotFoundException("Read-only parent: " + parentId);
        Optional<FileWrapper> existing = s.context.getByPath(documentId).join();

        ParcelFileDescriptor[] pipe = createPipeOrThrow();
        streamingPool.execute(() -> drainPipeAndUpload(parent, parentId, name, existing, pipe[0], s, signal));
        return pipe[1];
    }

    private void drainPipeAndUpload(FileWrapper parent,
                                    String parentId,
                                    String name,
                                    Optional<FileWrapper> existing,
                                    ParcelFileDescriptor readFd,
                                    Session s,
                                    CancellationSignal signal) {
        File temp = null;
        UploadProgressNotifier.Handle handle = null;
        try {
            temp = File.createTempFile("peergos-upload-", ".tmp", getContext().getCacheDir());
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(readFd);
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (signal != null && signal.isCanceled()) return;
                    out.write(buf, 0, n);
                }
            }
            long size = temp.length();
            handle = progressNotifier().start(name, size);
            UploadProgressNotifier.Handle h = handle;
            LocalDateTime modTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(temp.lastModified()), ZoneOffset.UTC);
            HashTree hash = null;
            Optional<Thumbnail> thumb = Optional.empty();
            if (size > 0) {
                try (FileAsyncReader r = new FileAsyncReader(temp)) {
                    hash = HashTree.build(r, (int) (size >>> 32), (int) size, s.crypto.hasher).join();
                }
                thumb = generateThumbnail(temp, name);
            }
            Optional<HashTree> hashOpt = Optional.ofNullable(hash);
            FileWrapper parentForUpload = parent;
            boolean existsAsFile = existing.isPresent() && !existing.get().isDirectory();
            boolean existingIsEmptyPlaceholder = existsAsFile && existing.get().getSize() == 0;
            if (existingIsEmptyPlaceholder) {
                existing.get().remove(parent, PathUtil.get(joinPath(parentId, name)), s.context).join();
                parentForUpload = s.context.getByPath(parentId).join().orElse(parent);
                existsAsFile = false;
            }
            if (existsAsFile) {
                FileWrapper target = existing.get();
                if (size == 0) {
                    target.overwriteFile(AsyncReader.build(new byte[0]), 0,
                            s.network, s.crypto, p -> {}).join();
                } else {
                    try (FileAsyncReader reader = new FileAsyncReader(temp)) {
                        target.overwriteFile(reader, size, s.network, s.crypto, h::onBytes).join();
                    }
                }
            } else if (size == 0) {
                parentForUpload.uploadFileWithHash(name, AsyncReader.build(new byte[0]), 0,
                        Optional.empty(), Optional.of(modTime), Optional.empty(),
                        s.network, s.crypto, p -> {}).join();
            } else {
                try (FileAsyncReader reader = new FileAsyncReader(temp)) {
                    parentForUpload.uploadFileWithHash(name, reader, size,
                            hashOpt, Optional.of(modTime), thumb,
                            s.network, s.crypto, h::onBytes).join();
                }
            }
            handle.finish();
            notifyParent(parentId);
        } catch (IOException e) {
            if (!isPipeClosed(e)) {
                Log.w(TAG, "drain to upload failed", e);
                if (handle != null) handle.fail(e.getMessage() != null ? e.getMessage() : "I/O error");
            } else if (handle != null) handle.finish();
        } catch (Exception e) {
            Log.w(TAG, "drain to upload failed", e);
            if (handle != null) handle.fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp.toPath()); } catch (IOException ignored) {}
        }
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint,
                                                    CancellationSignal signal)
            throws FileNotFoundException {
        FileWrapper fw = lookupOrThrow(documentId);
        Optional<peergos.shared.user.fs.Thumbnail> thumb = fw.getFileProperties().thumbnail;
        if (thumb.isEmpty()) throw new FileNotFoundException("No thumbnail for " + documentId);
        byte[] bytes = thumb.get().data;
        ParcelFileDescriptor[] pipe = createPipeOrThrow();
        ParcelFileDescriptor readFd = pipe[0];
        ParcelFileDescriptor writeFd = pipe[1];
        streamingPool.execute(() -> {
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeFd)) {
                out.write(bytes);
            } catch (IOException e) {
                if (!isPipeClosed(e)) Log.w(TAG, "thumbnail write failed", e);
            }
        });
        return new AssetFileDescriptor(readFd, 0, bytes.length);
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        Session s = sessionOrThrow();
        FileWrapper parent = lookupOrThrow(parentDocumentId);
        if (!parent.isWritable()) throw new FileNotFoundException("Read-only: " + parentDocumentId);
        try {
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                parent.mkdir(displayName, s.network, false, parent.mirrorBatId(), s.crypto).join();
            } else {
                parent.uploadFileWithHash(displayName, AsyncReader.build(new byte[0]), 0,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        s.network, s.crypto, p -> {}).join();
            }
        } catch (Exception e) {
            throw rethrowAsFnf("createDocument", e);
        }
        notifyParent(parentDocumentId);
        return joinPath(parentDocumentId, displayName);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Session s = sessionOrThrow();
        int slash = documentId.lastIndexOf('/');
        if (slash <= 0) throw new FileNotFoundException("Cannot delete root: " + documentId);
        String parentId = documentId.substring(0, slash);
        FileWrapper parent = lookupOrThrow(parentId);
        if (!parent.isWritable()) throw new FileNotFoundException("Read-only parent: " + parentId);
        FileWrapper fw = lookupOrThrow(documentId);
        try {
            fw.remove(parent, PathUtil.get(documentId), s.context).join();
        } catch (Exception e) {
            throw rethrowAsFnf("deleteDocument", e);
        }
        notifyParent(parentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        Session s = sessionOrThrow();
        int slash = documentId.lastIndexOf('/');
        if (slash <= 0) throw new FileNotFoundException("Cannot rename root: " + documentId);
        String parentId = documentId.substring(0, slash);
        FileWrapper parent = lookupOrThrow(parentId);
        if (!parent.isWritable()) throw new FileNotFoundException("Read-only parent: " + parentId);
        FileWrapper fw = lookupOrThrow(documentId);
        try {
            fw.rename(displayName, parent, PathUtil.get(documentId), s.context).join();
        } catch (Exception e) {
            throw rethrowAsFnf("renameDocument", e);
        }
        notifyParent(parentId);
        return joinPath(parentId, displayName);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.equals(parentDocumentId)
                || documentId.startsWith(parentDocumentId + "/");
    }

    @Override
    public DocumentsContract.Path findDocumentPath(String parentDocumentId, String childDocumentId)
            throws FileNotFoundException {
        if (childDocumentId == null || childDocumentId.isEmpty() || childDocumentId.charAt(0) != '/')
            throw new FileNotFoundException(childDocumentId);
        int secondSlash = childDocumentId.indexOf('/', 1);
        String rootId = secondSlash < 0 ? childDocumentId.substring(1)
                : childDocumentId.substring(1, secondSlash);

        java.util.List<String> chain = new java.util.ArrayList<>();
        if (parentDocumentId == null) {
            chain.add("/" + rootId);
        } else {
            if (!isChildDocument(parentDocumentId, childDocumentId))
                throw new FileNotFoundException(childDocumentId + " is not under " + parentDocumentId);
            chain.add(parentDocumentId);
        }
        String start = chain.get(0);
        int from = start.length();
        while (from < childDocumentId.length()) {
            int next = childDocumentId.indexOf('/', from + 1);
            if (next < 0) { chain.add(childDocumentId); break; }
            chain.add(childDocumentId.substring(0, next));
            from = next;
        }
        if (!chain.get(chain.size() - 1).equals(childDocumentId))
            chain.add(childDocumentId);
        return new DocumentsContract.Path(parentDocumentId == null ? rootId : null, chain);
    }

    /** Backs a seekable {@link ParcelFileDescriptor} with a Peergos {@link AsyncReader}.
     *  All callbacks are serialised on the Handler thread we hand to
     *  {@code openProxyFileDescriptor}, so the cached reader + position is single-threaded
     *  and we avoid a seek when reads are sequential — the common video / image case. */
    private static final class PeergosProxyCallback extends ProxyFileDescriptorCallback {
        private final FileWrapper fw;
        private final long size;
        private final Session s;
        private final HandlerThread thread;
        private AsyncReader reader;
        private long pos;

        PeergosProxyCallback(FileWrapper fw, long size, Session s, HandlerThread thread) {
            this.fw = fw;
            this.size = size;
            this.s = s;
            this.thread = thread;
        }

        @Override
        public long onGetSize() {
            return size;
        }

        @Override
        public int onRead(long offset, int len, byte[] data) throws ErrnoException {
            if (offset >= size) return 0;
            int toRead = (int) Math.min(len, size - offset);
            try {
                if (reader == null) {
                    reader = fw.getInputStream(s.network, s.crypto, size, p -> {}).join();
                    pos = 0;
                }
                if (offset != pos) {
                    reader = reader.seek(offset).join();
                    pos = offset;
                }
                int total = 0;
                while (total < toRead) {
                    int n = reader.readIntoArray(data, total, toRead - total).join();
                    if (n <= 0) break;
                    total += n;
                    pos += n;
                }
                return total;
            } catch (Exception e) {
                Log.w(TAG, "proxy onRead failed at offset=" + offset + " len=" + len, e);
                // Drop the stale reader so the next call re-opens from scratch.
                try { if (reader != null) reader.close(); } catch (Exception ignored) {}
                reader = null;
                pos = 0;
                throw new ErrnoException("onRead", OsConstants.EIO);
            }
        }

        @Override
        public void onRelease() {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            reader = null;
            thread.quitSafely();
        }
    }

    private static boolean isPipeClosed(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("EPIPE") || m.contains("Broken pipe"));
    }

    private static Optional<Thumbnail> generateThumbnail(File temp, String name) {
        try {
            byte[] head = new byte[Math.min((int) temp.length(), MimeTypes.HEADER_BYTES_TO_IDENTIFY_MIME_TYPE)];
            try (java.io.InputStream in = new java.io.FileInputStream(temp)) {
                int got = 0;
                while (got < head.length) {
                    int n = in.read(head, got, head.length - got);
                    if (n <= 0) break;
                    got += n;
                }
            }
            String mime = MimeTypes.calculateMimeType(head, name);
            if (mime.startsWith("image/")) {
                long len = temp.length();
                if (len > 64L * 1024 * 1024) return Optional.empty();
                byte[] bytes = Files.readAllBytes(temp.toPath());
                return new AndroidImageThumbnailer().generateThumbnail(bytes);
            }
            if (mime.startsWith("video/"))
                return MainActivity.generateVideoThumbnail(temp);
            return Optional.empty();
        } catch (Exception e) {
            Log.w(TAG, "thumbnail generation failed", e);
            return Optional.empty();
        }
    }

    private void addRow(MatrixCursor cursor, FileWrapper fw, String documentId, boolean parentWritable) {
        boolean isDir = fw.isDirectory();
        boolean selfWritable = fw.isWritable();
        String mime = isDir ? Document.MIME_TYPE_DIR : fw.getFileProperties().mimeType;
        long size = isDir ? 0 : fw.getSize();
        LocalDateTime modified = fw.getFileProperties().modified;
        long modifiedMs = modified == null ? 0L
                : modified.toInstant(ZoneOffset.UTC).toEpochMilli();
        int flags = 0;
        if (parentWritable)
            flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        if (selfWritable) {
            if (isDir) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            else flags |= Document.FLAG_SUPPORTS_WRITE;
        }
        if (!isDir && fw.getFileProperties().thumbnail.isPresent())
            flags |= Document.FLAG_SUPPORTS_THUMBNAIL;

        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(Document.COLUMN_DISPLAY_NAME, fw.getName());
        row.add(Document.COLUMN_MIME_TYPE, mime);
        row.add(Document.COLUMN_SIZE, size);
        row.add(Document.COLUMN_LAST_MODIFIED, modifiedMs);
        row.add(Document.COLUMN_FLAGS, flags);
    }

    private ParcelFileDescriptor[] createPipeOrThrow() throws FileNotFoundException {
        try { return ParcelFileDescriptor.createPipe(); }
        catch (IOException e) { throw new FileNotFoundException("Pipe creation failed: " + e); }
    }

    private void notifyParent(String parentDocumentId) {
        Uri uri = DocumentsContract.buildChildDocumentsUri(
                DocumentsProviderBackend.AUTHORITY, parentDocumentId);
        getContext().getContentResolver().notifyChange(uri, null);
    }

    private static String joinPath(String parentDocumentId, String name) {
        return parentDocumentId.endsWith("/") ? parentDocumentId + name
                : parentDocumentId + "/" + name;
    }

    private static FileNotFoundException rethrowAsFnf(String op, Exception cause) {
        FileNotFoundException fnf = new FileNotFoundException(op + ": " + cause.getMessage());
        fnf.initCause(cause);
        return fnf;
    }

    private static class Session {
        final UserContext context;
        final NetworkAccess network;
        final Crypto crypto;
        Session(UserContext c, NetworkAccess n, Crypto cr) { context = c; network = n; crypto = cr; }
    }

    private Session sessionOrThrow() throws FileNotFoundException {
        Optional<UserContext> ctx = PeergosSession.context();
        Optional<NetworkAccess> net = PeergosSession.network();
        Optional<Crypto> crypto = PeergosSession.crypto();
        if (ctx.isEmpty() || net.isEmpty() || crypto.isEmpty())
            throw new FileNotFoundException("Peergos not signed in");
        return new Session(ctx.get(), net.get(), crypto.get());
    }

    private FileWrapper lookupOrThrow(String documentId) throws FileNotFoundException {
        Session s = sessionOrThrow();
        Optional<FileWrapper> fw = s.context.getByPath(documentId).join();
        if (fw.isEmpty()) throw new FileNotFoundException(documentId);
        return fw.get();
    }
}
