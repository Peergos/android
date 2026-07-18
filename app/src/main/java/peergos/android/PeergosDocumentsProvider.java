package peergos.android;

import android.content.ContentResolver;
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.MimeTypes;
import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;
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
        ThumbnailGenerator.setInstance(new AndroidImageThumbnailer());
        ThumbnailGenerator.setVideoInstance(MainActivity::generateVideoThumbnail);
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
        boolean wantsWrite = mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0;
        return wantsWrite
                ? openWritable(documentId, mode, signal)
                : openForRead(documentId, signal);
    }

    private ParcelFileDescriptor openForRead(String documentId, CancellationSignal signal)
            throws FileNotFoundException {
        // Hand back a seekable FD via StorageManager.openProxyFileDescriptor so image and
        // video viewers (BitmapFactory header peek, MP4 moov-atom lookup) can lseek. A
        // pipe is non-seekable and silently breaks both — that's the blank-screen path.
        Session s = sessionOrThrow();
        FileWrapper fw = lookupOrThrow(documentId);
        long size = fw.getSize();
        Context appCtx = getContext().getApplicationContext();
        StorageManager sm = (StorageManager) appCtx.getSystemService(Context.STORAGE_SERVICE);
        HandlerThread thread = new HandlerThread("PeergosProxyFd-" + Integer.toHexString(documentId.hashCode()));
        thread.start();
        // Keep the process out of Doze for the lifetime of the FD; released in onRelease.
        StreamingForegroundService.acquire(appCtx);
        try {
            return sm.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    new PeergosProxyCallback(fw, size, s, thread, appCtx),
                    new Handler(thread.getLooper()));
        } catch (IOException e) {
            thread.quitSafely();
            StreamingForegroundService.release(appCtx);
            throw rethrowAsFnf("openProxyFileDescriptor " + documentId, e);
        }
    }

    private ParcelFileDescriptor openWritable(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        Session s = sessionOrThrow();
        int slash = documentId.lastIndexOf('/');
        if (slash <= 0) throw new FileNotFoundException("Cannot write to root: " + documentId);
        String parentId = documentId.substring(0, slash);
        String name = documentId.substring(slash + 1);
        FileWrapper parent = lookupOrThrow(parentId);
        if (!parent.isWritable()) throw new FileNotFoundException("Read-only parent: " + parentId);
        Optional<FileWrapper> existingOpt = s.context.getByPath(documentId).join();

        // POSIX-ish semantics: 'w' without 'a' truncates an existing file; 'a' (append) leaves
        // the existing content alone. Missing files always get a zero-byte placeholder so
        // every subsequent onWrite can use overwriteSection against a real FileWrapper.
        boolean appendMode = mode.indexOf('a') >= 0;
        boolean truncateMode = !appendMode && mode.indexOf('w') >= 0;
        FileWrapper fw;
        try {
            if (existingOpt.isEmpty()) {
                parent.uploadFileWithHash(name, AsyncReader.build(new byte[0]), 0,
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        s.network, s.crypto, p -> {}).join();
                fw = s.context.getByPath(documentId).join()
                        .orElseThrow(() -> new FileNotFoundException("After create: " + documentId));
            } else if (existingOpt.get().isDirectory()) {
                throw new FileNotFoundException("Is a directory: " + documentId);
            } else {
                fw = existingOpt.get();
                if (truncateMode && fw.getSize() > 0)
                    fw = fw.truncate(0, s.network, s.crypto).join();
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw rethrowAsFnf("prepare write " + documentId, e);
        }

        Context appCtx = getContext().getApplicationContext();
        StorageManager sm = (StorageManager) appCtx.getSystemService(Context.STORAGE_SERVICE);
        HandlerThread thread = new HandlerThread("PeergosProxyFd-" + Integer.toHexString(documentId.hashCode()));
        thread.start();
        StreamingForegroundService.acquire(appCtx);
        // Indeterminate progress: the editor decides the total size as it writes, so we
        // don't know it up front. start(name, 0) renders an indeterminate bar.
        UploadProgressNotifier.Handle handle = progressNotifier().start(name, 0);
        File staging;
        try {
            staging = File.createTempFile("peergos-write-", ".tmp", appCtx.getCacheDir());
        } catch (IOException e) {
            thread.quitSafely();
            StreamingForegroundService.release(appCtx);
            handle.fail("could not stage write");
            throw rethrowAsFnf("staging file for " + documentId, e);
        }
        // Only a non-truncating open needs the existing bytes staged before the first write.
        boolean needsPrefill = !truncateMode && fw.getSize() > 0;
        try {
            return sm.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_WRITE,
                    new PeergosWriteProxyCallback(fw, fw.getSize(), parentId, name, s, thread,
                            appCtx, appCtx.getContentResolver(), streamingPool, handle, staging, needsPrefill),
                    new Handler(thread.getLooper()));
        } catch (IOException e) {
            thread.quitSafely();
            StreamingForegroundService.release(appCtx);
            handle.fail(e.getMessage() != null ? e.getMessage() : "open failed");
            try { Files.deleteIfExists(staging.toPath()); } catch (IOException ignored) {}
            throw rethrowAsFnf("openProxyFileDescriptor " + documentId, e);
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
        private final Context appCtx;
        private AsyncReader reader;
        private long pos;

        PeergosProxyCallback(FileWrapper fw, long size, Session s, HandlerThread thread, Context appCtx) {
            this.fw = fw;
            this.size = size;
            this.s = s;
            this.thread = thread;
            this.appCtx = appCtx;
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
            StreamingForegroundService.release(appCtx);
        }
    }

    /** Read-write proxy callback. Writes land in a staging file in the cache dir, and the
     *  whole thing is uploaded once on release via the normal {@code uploadFileSection}
     *  path — the same one in-app uploads use.
     *
     *  The upload runs on {@code postReleasePool} so the FD-close path doesn't block on
     *  network I/O; the foreground service stays acquired until it completes. */
    private static final class PeergosWriteProxyCallback extends ProxyFileDescriptorCallback {
        private final String parentId;
        private final String name;
        private final Session s;
        private final HandlerThread thread;
        private final Context appCtx;
        private final ContentResolver cr;
        private final ExecutorService postReleasePool;
        private final UploadProgressNotifier.Handle handle;
        private final File staging;
        private final FileWrapper fw;
        private final boolean needsPrefill;
        private RandomAccessFile raf;
        private long size;
        private boolean dirty;
        private String writeError;

        PeergosWriteProxyCallback(FileWrapper fw, long size, String parentId, String name,
                                  Session s, HandlerThread thread, Context appCtx,
                                  ContentResolver cr, ExecutorService postReleasePool,
                                  UploadProgressNotifier.Handle handle, File staging,
                                  boolean needsPrefill) {
            this.fw = fw;
            this.size = size;
            this.parentId = parentId;
            this.name = name;
            this.s = s;
            this.thread = thread;
            this.appCtx = appCtx;
            this.cr = cr;
            this.postReleasePool = postReleasePool;
            this.handle = handle;
            this.staging = staging;
            this.needsPrefill = needsPrefill;
        }

        /** Opens the staging file, and for a non-truncating open pulls the existing content
         *  down into it first. Deferred to the first callback so that network read happens on
         *  our handler thread rather than the binder thread that called openDocument. */
        private RandomAccessFile staged() throws IOException {
            if (raf != null) return raf;
            raf = new RandomAccessFile(staging, "rw");
            if (needsPrefill) {
                long remaining = size;
                try (AsyncReader r = fw.getInputStream(s.network, s.crypto, size, p -> {}).join()) {
                    byte[] buf = new byte[64 * 1024];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int n = r.readIntoArray(buf, 0, toRead).join();
                        if (n <= 0) break;
                        raf.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                if (remaining > 0)
                    throw new IOException("Short read staging " + name + ": " + remaining + " bytes missing");
            }
            return raf;
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
                RandomAccessFile f = staged();
                f.seek(offset);
                int total = 0;
                while (total < toRead) {
                    int n = f.read(data, total, toRead - total);
                    if (n <= 0) break;
                    total += n;
                }
                return total;
            } catch (Exception e) {
                Log.w(TAG, "write-proxy onRead failed at offset=" + offset + " len=" + len, e);
                throw new ErrnoException("onRead", OsConstants.EIO);
            }
        }

        @Override
        public int onWrite(long offset, int len, byte[] data) throws ErrnoException {
            try {
                RandomAccessFile f = staged();
                f.seek(offset);
                f.write(data, 0, len);
                if (offset + len > size) size = offset + len;
                dirty = true;
                return len;
            } catch (Exception e) {
                Log.w(TAG, "onWrite failed at offset=" + offset + " len=" + len, e);
                writeError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw new ErrnoException("onWrite", OsConstants.EIO);
            }
        }

        @Override
        public void onFsync() {
            // Nothing reaches Peergos until release, so there is nothing to flush.
        }

        @Override
        public void onRelease() {
            try { if (raf != null) raf.close(); } catch (Exception ignored) {}
            raf = null;
            thread.quitSafely();
            final long finalSize = size;
            final boolean wroteAnything = dirty;
            final String openError = writeError;
            postReleasePool.execute(() -> {
                String error = openError;
                try {
                    if (wroteAnything && error == null) {
                        try {
                            uploadStaged(finalSize);
                        } catch (Exception e) {
                            Log.w(TAG, "upload of " + name + " failed", e);
                            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        }
                    }
                    if (error == null) handle.finish();
                    else handle.fail(error);
                } finally {
                    try { Files.deleteIfExists(staging.toPath()); } catch (IOException ignored) {}
                    cr.notifyChange(DocumentsContract.buildChildDocumentsUri(
                            DocumentsProviderBackend.AUTHORITY, parentId), null);
                    StreamingForegroundService.release(appCtx);
                }
            });
        }

        private void uploadStaged(long finalSize) {
            FileWrapper parent = s.context.getByPath(parentId).join()
                    .orElseThrow(() -> new IllegalStateException("Parent vanished: " + parentId));
            AsyncReader reader = stagingReader();
            try {
                parent.uploadFileSection(name, reader, false, 0, finalSize, Optional.empty(), true,
                        s.network, s.crypto, () -> false, handle::onBytes).join();
            } finally {
                try { reader.close(); } catch (Exception ignored) {}
            }
            addVideoThumbnail();
        }

        private AsyncReader stagingReader() {
            return new AndroidAsyncReader(openStaging(), this::openStaging);
        }

        private java.io.FileInputStream openStaging() {
            try {
                return new java.io.FileInputStream(staging);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        private void addVideoThumbnail() {
            String mime = calculateMimeType(staging, name);
            if (mime == null || !mime.startsWith("video/")) return;
            Optional<Thumbnail> thumb = MainActivity.generateVideoThumbnail(staging);
            if (thumb.isEmpty()) return;
            Optional<FileWrapper> uploaded = s.context.getByPath(joinPath(parentId, name)).join();
            if (uploaded.isEmpty()) return;
            FileProperties current = uploaded.get().getFileProperties();
            FileProperties updated = new FileProperties(current.name, current.isDirectory, current.isLink,
                    current.mimeType, current.size, current.modified, current.created,
                    current.isHidden, thumb, current.streamSecret, current.treeHash);
            uploaded.get().setSameNameProperties(updated, s.network).join();
        }
    }

    private static boolean isPipeClosed(IOException e) {
        String m = e.getMessage();
        return m != null && (m.contains("EPIPE") || m.contains("Broken pipe"));
    }

    private static String calculateMimeType(File temp, String name) {
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
            return MimeTypes.calculateMimeType(head, name);
        } catch (Exception e) {
            Log.w(TAG, "mimetype detection failed for " + name, e);
            return null;
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
