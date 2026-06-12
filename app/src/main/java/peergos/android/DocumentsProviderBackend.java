package peergos.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.nio.file.Path;
import java.util.Optional;

import peergos.server.mount.MountBackend;
import peergos.server.webdav.MountConfig;
import peergos.shared.user.UserContext;

public class DocumentsProviderBackend implements MountBackend {

    public static final String AUTHORITY = "peergos.android.documents";

    private final Context appContext;
    private volatile boolean active = false;

    public DocumentsProviderBackend(Context appContext) {
        this.appContext = appContext;
    }

    @Override
    public void enable(MountConfig config, UserContext context, Path peergosDir) {
        PeergosSession.publish(context, context.network, context.crypto);
        active = true;
        notifyRoots();
    }

    @Override
    public void disable() {
        PeergosSession.clear();
        active = false;
        notifyRoots();
    }

    @Override
    public Optional<String> activeMountPoint() {
        return active ? Optional.of("Files app") : Optional.empty();
    }

    private void notifyRoots() {
        ContentResolver cr = appContext.getContentResolver();
        Uri roots = DocumentsContract.buildRootsUri(AUTHORITY);
        cr.notifyChange(roots, null);
    }
}
