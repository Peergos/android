package peergos.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.nio.file.Path;
import java.util.Optional;

import peergos.server.mount.MountBackend;
import peergos.server.webdav.MountConfig;
import peergos.android.calendar.CalendarPermission;
import peergos.android.calendar.PeergosAccount;
import peergos.shared.user.UserContext;

public class DocumentsProviderBackend implements MountBackend {

    private static final String TAG = "PeergosMount";
    public static final String AUTHORITY = "peergos.android.documents";

    private final Context appContext;
    private volatile boolean active = false;

    public DocumentsProviderBackend(Context appContext) {
        this.appContext = appContext;
    }

    @Override
    public void enable(MountConfig config, UserContext context, Path peergosDir) {
        // One login serves both: the session is published whichever feature asked for it, so
        // syncing the calendar alone needs no second credential and shows no drive.
        PeergosSession.publish(context, context.network, context.crypto);
        active = config.mountDrive;
        notifyRoots();
        if (! config.syncCalendar) {
            PeergosAccount.stopSyncing(appContext);
            return;
        }
        // This is the one moment the app has a signed-in session without the WebView, which
        // is exactly what the calendar sync adapter needs, so the account is registered here
        // rather than given a credential path of its own.
        CalendarPermission.onCalendarStarted();
        try {
            PeergosAccount.requestSync(PeergosAccount.ensure(appContext, context.username));
        } catch (RuntimeException e) {
            // A missing accounts permission should not take the rest of the login down with it.
            Log.w(TAG, "Could not register the Peergos account for calendar sync", e);
        }
    }

    /**
     * Leaves the account and its calendars in place. Without a session the sync adapter is
     * a no-op, so the calendars simply stop updating; removing the account would delete
     * them from the device every time the mount was toggled.
     */
    @Override
    public void disable() {
        PeergosSession.clear();
        active = false;
        CalendarPermission.onCalendarStopped();
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
