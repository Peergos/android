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
    private static final String EXTRA_URI = "uri";

    /** Test-only: wipe the root and (optionally) seed it with N random files
     *  in the provider's own process. extras: count (int), seedLow (long, low
     *  32 bits of the seed), minSize (long), maxSize (long). */
    public static final String METHOD_RESET_AND_SEED = "test:resetAndSeed";

    private File rootDir;

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
            if (METHOD_RESET_AND_SEED.equals(method)) {
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

    private void resetAndSeed(int count, long seed, long minSize, long maxSize) throws IOException {
        deleteRecursive(rootDir);
        rootDir.mkdirs();
        java.util.Random rng = new java.util.Random(seed);
        byte[] buf = new byte[64 * 1024];
        for (int i = 0; i < count; i++) {
            long size = minSize + (long) (rng.nextDouble() * (maxSize - minSize));
            File f = new File(rootDir, String.format("img-%05d.bin", i));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                long remaining = size;
                while (remaining > 0) {
                    int n = (int) Math.min(buf.length, remaining);
                    rng.nextBytes(buf);
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
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
