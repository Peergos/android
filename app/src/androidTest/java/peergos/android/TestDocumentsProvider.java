package peergos.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

public class TestDocumentsProvider extends DocumentsProvider {

    public static final String AUTHORITY = "peergos.android.test.documents";
    public static final String ROOT_ID = "root";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
    };

    private File rootDir;

    @Override
    public boolean onCreate() {
        rootDir = new File(getContext().getCacheDir(), "test-sync-root");
        if (!rootDir.exists() && !rootDir.mkdirs())
            throw new IllegalStateException("Failed to create " + rootDir);
        return true;
    }

    public static File resetRoot(android.content.Context ctx) {
        File root = new File(ctx.getCacheDir(), "test-sync-root");
        deleteRecursive(root);
        if (!root.mkdirs())
            throw new IllegalStateException("Failed to create " + root);
        return root;
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory())
            for (File c : Objects.requireNonNull(f.listFiles()))
                deleteRecursive(c);
        f.delete();
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        MatrixCursor.RowBuilder row = c.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_TITLE, "Peergos Test Root");
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_LOCAL_ONLY);
        row.add(DocumentsContract.Root.COLUMN_ICON, android.R.drawable.sym_def_app_icon);
        return c;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        addRow(c, fileFor(documentId), documentId);
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        if (!parent.isDirectory())
            throw new FileNotFoundException(parentDocumentId + " is not a directory");
        MatrixCursor c = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File[] children = parent.listFiles();
        if (children == null) return c;
        for (File child : children)
            addRow(c, child, docIdFor(child));
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = fileFor(parentDocumentId);
        File child = new File(parent, displayName);
        try {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!child.mkdir() && !child.isDirectory())
                    throw new FileNotFoundException("mkdir failed: " + child);
            } else {
                if (!child.createNewFile())
                    throw new FileNotFoundException("createNewFile failed: " + child);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return docIdFor(child);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File f = fileFor(documentId);
        deleteRecursive(f);
        if (f.exists())
            throw new FileNotFoundException("delete failed: " + documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File src = fileFor(documentId);
        File dst = new File(src.getParentFile(), displayName);
        if (!src.renameTo(dst))
            throw new FileNotFoundException("rename failed: " + documentId + " -> " + displayName);
        return docIdFor(dst);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.equals(parentDocumentId)
                || documentId.startsWith(parentDocumentId + "/");
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
        if (ROOT_ID.equals(documentId))
            return rootDir;
        if (!documentId.startsWith(ROOT_ID + "/"))
            throw new FileNotFoundException("doc outside tree: " + documentId);
        File f = new File(rootDir, documentId.substring(ROOT_ID.length() + 1));
        if (!f.exists())
            throw new FileNotFoundException(documentId);
        return f;
    }

    private String docIdFor(File f) {
        if (f.equals(rootDir))
            return ROOT_ID;
        String rel = rootDir.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
        return ROOT_ID + "/" + rel;
    }

    private static String mimeOf(File f) {
        if (f.isDirectory())
            return DocumentsContract.Document.MIME_TYPE_DIR;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = name.substring(dot + 1).toLowerCase();
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }
}
