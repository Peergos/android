package peergos.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.ArrayList;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/** A plain ContentProvider (deliberately NOT a DocumentsProvider, because
 *  android.provider.DocumentsProvider.attachInfo on API 33+ rejects any
 *  exported provider that isn't system-privileged via MANAGE_DOCUMENTS).
 *  We hand-implement the DocumentsContract URI shapes and call() methods
 *  that androidx.documentfile and ContentResolver use, so AndroidSyncFileSystem
 *  can talk to us through DocumentFile.fromTreeUri exactly as it would a real
 *  SAF provider. Backed by a directory in this provider's own process's
 *  cacheDir; the test seeds files via the METHOD_RESET_AND_SEED call so all
 *  file IO happens in the provider's process (static state doesn't cross
 *  the process boundary). */
public class TestDocumentsProvider extends ContentProvider {

    public static final String AUTHORITY = "peergos.android.test.documents";
    public static final String ROOT_ID = "root";

    private static final int MATCH_TREE = 1;          // content://AUTH/tree/{treeId}
    private static final int MATCH_DOCUMENT = 2;      // .../tree/{treeId}/document/{docId}
    private static final int MATCH_CHILDREN = 3;      // .../tree/{treeId}/document/{docId}/children

    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        MATCHER.addURI(AUTHORITY, "tree/*", MATCH_TREE);
        MATCHER.addURI(AUTHORITY, "tree/*/document/*", MATCH_DOCUMENT);
        MATCHER.addURI(AUTHORITY, "tree/*/document/*/children", MATCH_CHILDREN);
    }

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
    };

    // DocumentsContract.METHOD_* and EXTRA_URI are @hide in the SDK but the
    // wire strings are stable (used by androidx.documentfile, the SAF client).
    private static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    private static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
    private static final String METHOD_RENAME_DOCUMENT = "android:renameDocument";
    private static final String METHOD_MOVE_DOCUMENT = "android:moveDocument";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_TARGET_URI = "android.content.extra.TARGET_URI";

    /** Test-only: wipe the root and (optionally) seed it with N random files
     *  in the provider's own process. extras: count (int), seedLow (long, low
     *  32 bits of the seed), minSize (long), maxSize (long), and moveSupported (boolean, default true), as not
     *  every provider can move documents. */
    public static final String METHOD_RESET_AND_SEED = "test:resetAndSeed";
    /** Test-only: every file and folder under the root, but the sync's staging folder, with each file's size, first
     *  64 bytes and SHA-256, worked out here so no file has to cross the process boundary. Also whether the staging
     *  folder is there. */
    public static final String METHOD_TREE = "test:tree";
    /** Test-only: sets the modification time of the file at the path in arg to extras' time, as SAF can't */
    public static final String METHOD_SET_MODIFIED = "test:setModified";
    /** Test-only: moves what is at the path in arg to extras' to, folders included, as another app editing the files
     *  would. An error, such as there being nothing to move, is returned as error, as exceptions don't cross processes */
    public static final String METHOD_MOVE_PATH = "test:move";
    private static final String STAGING_DIR = ".peergos-partial-downloads";

    private File rootDir;
    private volatile boolean moveSupported = true;

    @Override
    public boolean onCreate() {
        rootDir = new File(getContext().getCacheDir(), "test-sync-root");
        rootDir.mkdirs();
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            switch (MATCHER.match(uri)) {
                case MATCH_TREE: {
                    String docId = DocumentsContract.getTreeDocumentId(uri);
                    return queryOne(docId, projection);
                }
                case MATCH_DOCUMENT: {
                    String docId = DocumentsContract.getDocumentId(uri);
                    return queryOne(docId, projection);
                }
                case MATCH_CHILDREN: {
                    String parentId = DocumentsContract.getDocumentId(uri);
                    return queryChildren(parentId, projection);
                }
            }
        } catch (FileNotFoundException e) {
            return null;
        }
        return null;
    }

    private Cursor queryOne(String docId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        addRow(c, fileFor(docId), docId);
        return c;
    }

    private Cursor queryChildren(String parentId, String[] projection) throws FileNotFoundException {
        File parent = fileFor(parentId);
        if (!parent.isDirectory()) throw new FileNotFoundException(parentId + " not a directory");
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File[] kids = parent.listFiles();
        if (kids == null) return c;
        for (File k : kids) addRow(c, k, docIdFor(k));
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (MATCHER.match(uri) != MATCH_DOCUMENT)
            throw new FileNotFoundException("Not a document URI: " + uri);
        String docId = DocumentsContract.getDocumentId(uri);
        return ParcelFileDescriptor.open(fileFor(docId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        try {
            switch (MATCHER.match(uri)) {
                case MATCH_TREE:
                    return mimeOf(fileFor(DocumentsContract.getTreeDocumentId(uri)));
                case MATCH_DOCUMENT:
                    return mimeOf(fileFor(DocumentsContract.getDocumentId(uri)));
                case MATCH_CHILDREN:
                    return DocumentsContract.Document.MIME_TYPE_DIR;
            }
        } catch (FileNotFoundException e) {
            return null;
        }
        return null;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        try {
            if (METHOD_CREATE_DOCUMENT.equals(method)) {
                Uri parentUri = extras.getParcelable(EXTRA_URI);
                String parentId = DocumentsContract.getDocumentId(parentUri);
                String mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
                String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                String childId = createChild(parentId, mime, name);
                Bundle out = new Bundle();
                out.putParcelable(EXTRA_URI,
                        DocumentsContract.buildDocumentUriUsingTree(parentUri, childId));
                return out;
            }
            if (METHOD_DELETE_DOCUMENT.equals(method)) {
                Uri docUri = extras.getParcelable(EXTRA_URI);
                String docId = DocumentsContract.getDocumentId(docUri);
                deleteRecursive(fileFor(docId));
                return null;
            }
            if (METHOD_RENAME_DOCUMENT.equals(method)) {
                Uri docUri = extras.getParcelable(EXTRA_URI);
                String docId = DocumentsContract.getDocumentId(docUri);
                String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                String newId = rename(docId, name);
                Bundle out = new Bundle();
                out.putParcelable(EXTRA_URI,
                        DocumentsContract.buildDocumentUriUsingTree(docUri, newId));
                return out;
            }
            if (METHOD_MOVE_DOCUMENT.equals(method)) {
                if (! moveSupported)
                    throw new UnsupportedOperationException("Move not supported");
                Uri docUri = extras.getParcelable(EXTRA_URI);
                Uri targetUri = extras.getParcelable(EXTRA_TARGET_URI);
                File src = fileFor(DocumentsContract.getDocumentId(docUri));
                File dst = new File(fileFor(DocumentsContract.getDocumentId(targetUri)), src.getName());
                if (dst.exists() || ! src.renameTo(dst))
                    throw new IOException("move failed: " + src + " -> " + dst);
                Bundle out = new Bundle();
                out.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(docUri, docIdFor(dst)));
                return out;
            }
            if (METHOD_TREE.equals(method))
                return tree();
            if (METHOD_SET_MODIFIED.equals(method)) {
                new File(rootDir, arg).setLastModified(extras.getLong("time"));
                return null;
            }
            if (METHOD_MOVE_PATH.equals(method)) {
                File src = new File(rootDir, arg), dst = new File(rootDir, extras.getString("to"));
                Bundle out = new Bundle();
                if (! src.exists())
                    out.putString("error", "gone");
                else {
                    dst.getParentFile().mkdirs();
                    if (dst.exists() || ! src.renameTo(dst))
                        out.putString("error", "move failed: " + arg + " -> " + extras.getString("to"));
                }
                return out;
            }
            if (METHOD_RESET_AND_SEED.equals(method)) {
                moveSupported = extras.getBoolean("moveSupported", true);
                int count = extras.getInt("count");
                long seed = extras.getLong("seed");
                long minSize = extras.getLong("minSize");
                long maxSize = extras.getLong("maxSize");
                resetAndSeed(count, seed, minSize, maxSize);
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return super.call(method, arg, extras);
    }

    private Bundle tree() throws Exception {
        ArrayList<String> paths = new ArrayList<>(), heads = new ArrayList<>(), digests = new ArrayList<>();
        ArrayList<Long> sizes = new ArrayList<>();
        ArrayList<Integer> dirs = new ArrayList<>();
        java.util.ArrayDeque<File> todo = new java.util.ArrayDeque<>(java.util.List.of(rootDir));
        while (! todo.isEmpty()) {
            File dir = todo.pop();
            File[] kids = dir.listFiles();
            if (kids == null)
                continue;
            for (File k : kids) {
                String rel = rootDir.toPath().relativize(k.toPath()).toString().replace(File.separatorChar, '/');
                if (rel.equals(STAGING_DIR) || rel.startsWith(STAGING_DIR + "/"))
                    continue;
                paths.add(rel);
                if (k.isDirectory()) {
                    dirs.add(1);
                    heads.add("");
                    digests.add("");
                    sizes.add(0L);
                    todo.push(k);
                    continue;
                }
                dirs.add(0);
                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                byte[] head = new byte[0];
                try (java.io.FileInputStream in = new java.io.FileInputStream(k)) {
                    byte[] buf = new byte[64 * 1024];
                    int read;
                    boolean first = true;
                    while ((read = in.read(buf)) > 0) {
                        if (first)
                            head = java.util.Arrays.copyOf(buf, Math.min(read, 64));
                        first = false;
                        sha.update(buf, 0, read);
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest())
                    hex.append(String.format("%02x", b));
                heads.add(android.util.Base64.encodeToString(head, android.util.Base64.NO_WRAP));
                digests.add(hex.toString());
                sizes.add(k.length());
            }
        }
        Bundle out = new Bundle();
        out.putStringArrayList("paths", paths);
        out.putStringArrayList("heads", heads);
        out.putStringArrayList("digests", digests);
        long[] sizeArray = new long[sizes.size()];
        int[] dirArray = new int[dirs.size()];
        for (int i = 0; i < sizeArray.length; i++) {
            sizeArray[i] = sizes.get(i);
            dirArray[i] = dirs.get(i);
        }
        out.putLongArray("sizes", sizeArray);
        out.putIntArray("dirs", dirArray);
        out.putBoolean("partial", new File(rootDir, STAGING_DIR).exists());
        return out;
    }

    private void resetAndSeed(int count, long seed, long minSize, long maxSize) throws IOException {
        deleteRecursive(rootDir);
        rootDir.mkdirs();
        java.util.Random rng = new java.util.Random(seed);
        for (int i = 0; i < count; i++) {
            File f = new File(rootDir, String.format("img-%05d.jpg", i));
            writeJpeg(f, rng, minSize, maxSize);
        }
    }

    /** Produces a real JPEG (decodable by BitmapFactory, exercised by the
     *  thumbnailer). Noise pixels barely compress, so JPEG bytes scale roughly
     *  linearly with pixel count; we pick dimensions to land near a target
     *  inside [minSize, maxSize]. If the encoder still overshoots, we drop
     *  quality; if it undershoots, we append random padding bytes
     *  (BitmapFactory ignores trailing bytes after the JPEG EOI marker). */
    private static void writeJpeg(File dst, java.util.Random rng, long minSize, long maxSize) throws IOException {
        long targetSize = minSize + (long) (rng.nextDouble() * (maxSize - minSize));
        int dim = (int) Math.round(Math.sqrt(targetSize / 1.4));
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                dim, dim, android.graphics.Bitmap.Config.ARGB_8888);
        try {
            int[] pixels = new int[dim * dim];
            for (int p = 0; p < pixels.length; p++) pixels[p] = rng.nextInt();
            bmp.setPixels(pixels, 0, dim, 0, 0, dim, dim);

            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            int quality = 95;
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, buf);
            while (buf.size() > maxSize && quality > 50) {
                quality -= 10;
                buf.reset();
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, buf);
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                buf.writeTo(out);
                long pad = minSize - buf.size();
                if (pad > 0) {
                    byte[] padBuf = new byte[64 * 1024];
                    while (pad > 0) {
                        int n = (int) Math.min(padBuf.length, pad);
                        rng.nextBytes(padBuf);
                        out.write(padBuf, 0, n);
                        pad -= n;
                    }
                }
            }
        } finally {
            bmp.recycle();
        }
    }

    private String createChild(String parentId, String mime, String displayName) throws IOException {
        File parent = fileFor(parentId);
        File child = new File(parent, displayName);
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            if (!child.mkdir() && !child.isDirectory()) throw new IOException("mkdir failed: " + child);
        } else {
            if (!child.createNewFile() && !child.exists()) throw new IOException("createNewFile failed: " + child);
        }
        return docIdFor(child);
    }

    private String rename(String docId, String displayName) throws IOException {
        File src = fileFor(docId);
        File dst = new File(src.getParentFile(), displayName);
        if (!src.renameTo(dst)) throw new IOException("rename failed: " + src + " -> " + dst);
        return docIdFor(dst);
    }

    private void addRow(MatrixCursor c, File f, String docId) {
        int flags = 0;
        if (f.isDirectory())
            flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        else
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        flags |= DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
        if (moveSupported)
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_MOVE;

        MatrixCursor.RowBuilder row = c.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, ROOT_ID.equals(docId) ? "root" : f.getName());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeOf(f));
        row.add(DocumentsContract.Document.COLUMN_SIZE, f.length());
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, f.lastModified());
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
    }

    private File fileFor(String documentId) throws FileNotFoundException {
        if (ROOT_ID.equals(documentId)) return rootDir;
        if (!documentId.startsWith(ROOT_ID + "/"))
            throw new FileNotFoundException("doc outside tree: " + documentId);
        File f = new File(rootDir, documentId.substring(ROOT_ID.length() + 1));
        if (!f.exists()) throw new FileNotFoundException(documentId);
        return f;
    }

    private String docIdFor(File f) {
        if (f.equals(rootDir)) return ROOT_ID;
        String rel = rootDir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
        return ROOT_ID + "/" + rel;
    }

    private static String mimeOf(File f) {
        if (f.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase();
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory())
            for (File c : Objects.requireNonNull(f.listFiles()))
                deleteRecursive(c);
        f.delete();
    }

    // ContentProvider abstract methods we don't use (tree URIs go through call())
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
