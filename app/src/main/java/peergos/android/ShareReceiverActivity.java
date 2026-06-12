package peergos.android;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ShareReceiverActivity extends Activity {

    private static final String TAG = "PeergosShareReceiver";
    private static final int REQUEST_CREATE = 4001;

    private static final String STATE_SOURCE = "peergos.share.source";
    private static final String STATE_NAME = "peergos.share.name";

    private Uri sourceUri;
    private String displayName;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (sourceUri != null) outState.putParcelable(STATE_SOURCE, sourceUri);
        if (displayName != null) outState.putString(STATE_NAME, displayName);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            sourceUri = savedInstanceState.getParcelable(STATE_SOURCE);
            displayName = savedInstanceState.getString(STATE_NAME);
            if (sourceUri != null) return;
        }

        Intent in = getIntent();
        String action = in == null ? null : in.getAction();
        if (!Intent.ACTION_SEND.equals(action)) {
            Toast.makeText(this, "Unsupported share action", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        sourceUri = in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (sourceUri == null) {
            Toast.makeText(this, "No file to share", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        String mimeType = in.getType();
        if (mimeType == null) mimeType = getContentResolver().getType(sourceUri);
        if (mimeType == null) mimeType = "application/octet-stream";

        displayName = queryDisplayName(sourceUri);
        if (displayName == null) displayName = defaultName(mimeType);

        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        create.addCategory(Intent.CATEGORY_OPENABLE);
        create.setType(mimeType);
        create.putExtra(Intent.EXTRA_TITLE, displayName);
        Uri rootDoc = peergosRootDocUri();
        if (rootDoc != null)
            create.putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootDoc);
        startActivityForResult(create, REQUEST_CREATE);
    }

    private static Uri peergosRootDocUri() {
        return PeergosSession.context()
                .map(ctx -> DocumentsContract.buildDocumentUri(
                        DocumentsProviderBackend.AUTHORITY, "/" + ctx.username))
                .orElse(null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CREATE) { finish(); return; }
        if (resultCode != Activity.RESULT_OK || data == null) { finish(); return; }
        Uri target = data.getData();
        if (target == null) { finish(); return; }

        new Thread(() -> {
            long copied = -1;
            String error = null;
            try {
                copied = copy(sourceUri, target);
                Log.i(TAG, "Share copy: " + copied + " bytes from " + sourceUri + " to " + target);
            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.w(TAG, "Share copy failed", t);
            }
            final long finalCopied = copied;
            final String finalError = error;
            runOnUiThread(() -> {
                String msg = finalError != null ? "Save failed: " + finalError
                        : "Saved " + finalCopied + " bytes to Peergos";
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
                finish();
            });
        }, "PeergosShareCopy").start();
    }

    private long copy(Uri source, Uri target) throws IOException {
        ContentResolver cr = getContentResolver();
        try (InputStream in = cr.openInputStream(source);
             OutputStream out = cr.openOutputStream(target)) {
            if (in == null) throw new IOException("openInputStream returned null for " + source);
            if (out == null) throw new IOException("openOutputStream returned null for " + target);
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            return total;
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (idx >= 0) {
                    String s = c.getString(idx);
                    if (s != null && !s.isEmpty()) return s;
                }
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty()) {
            int slash = last.lastIndexOf('/');
            return slash >= 0 ? last.substring(slash + 1) : last;
        }
        return null;
    }

    private static String defaultName(String mimeType) {
        long ts = System.currentTimeMillis();
        String ext;
        switch (mimeType) {
            case "image/jpeg": ext = ".jpg"; break;
            case "image/png":  ext = ".png"; break;
            case "image/webp": ext = ".webp"; break;
            case "video/mp4":  ext = ".mp4"; break;
            case "text/plain": ext = ".txt"; break;
            default:           ext = ""; break;
        }
        return "shared-" + ts + ext;
    }
}
