package peergos.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A ContentProvider that serves files from a folder upload session.
 * Each file is assigned a virtual URI whose DISPLAY_NAME is the full
 * relative path (e.g. "Photos/vacation/img.jpg"), so the WebView's
 * File object carries the directory structure without any index mapping.
 */
public class UploadFileProvider extends ContentProvider {

    public static final String AUTHORITY = "peergos.android.uploads";

    // session id -> (virtual path segment -> UploadEntry)
    private static final Map<String, Map<String, UploadEntry>> sessions = new HashMap<>();

    public static class UploadEntry {
        final Uri realUri;
        final String relativePath; // e.g. "Photos/vacation/img.jpg"

        public UploadEntry(Uri realUri, String relativePath) {
            this.realUri = realUri;
            this.relativePath = relativePath;
        }
    }

    public static String startSession() {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new HashMap<>());
        return id;
    }

    public static void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    /** Returns a virtual content URI whose DISPLAY_NAME will be the relativePath. */
    public static Uri addFile(String sessionId, Uri realUri, String relativePath) {
        Map<String, UploadEntry> session = sessions.get(sessionId);
        if (session == null) throw new IllegalStateException("Unknown upload session");
        // Use a unique key so two files with the same name in different dirs are distinct
        String key = UUID.randomUUID().toString();
        session.put(key, new UploadEntry(realUri, relativePath));
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(sessionId)
                .appendPath(key)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selArgs, String sortOrder) {
        UploadEntry entry = getEntry(uri);
        if (entry == null) return null;

        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }

        MatrixCursor cursor = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            switch (projection[i]) {
                case OpenableColumns.DISPLAY_NAME:
                    row[i] = entry.relativePath;
                    break;
                case OpenableColumns.SIZE:
                    try {
                        ParcelFileDescriptor pfd = getContext().getContentResolver()
                                .openFileDescriptor(entry.realUri, "r");
                        if (pfd != null) {
                            row[i] = pfd.getStatSize();
                            pfd.close();
                        }
                    } catch (Exception e) {
                        row[i] = 0L;
                    }
                    break;
                default:
                    row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        UploadEntry entry = getEntry(uri);
        if (entry == null) throw new FileNotFoundException("No entry for " + uri);
        try {
            return getContext().getContentResolver().openFileDescriptor(entry.realUri, mode);
        } catch (Exception e) {
            throw new FileNotFoundException("Cannot open " + entry.realUri + ": " + e.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        UploadEntry entry = getEntry(uri);
        if (entry == null) return "application/octet-stream";
        String type = getContext().getContentResolver().getType(entry.realUri);
        return type != null ? type : "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String sel, String[] args) { return 0; }

    private UploadEntry getEntry(Uri uri) {
        java.util.List<String> segs = uri.getPathSegments();
        if (segs.size() < 2) return null;
        String sessionId = segs.get(0);
        String key = segs.get(1);
        Map<String, UploadEntry> session = sessions.get(sessionId);
        if (session == null) return null;
        return session.get(key);
    }
}
