package peergos.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.DocumentsContract;
import android.util.Log;

import java.nio.file.Path;
import java.util.Optional;

import peergos.server.mount.MountBackend;
import peergos.server.webdav.MountConfig;
import peergos.android.sync.PeergosAccount;
import peergos.android.sync.SyncPermission;
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
        // One login serves all three: the session is published whichever feature asked for
        // it, so syncing the calendar alone needs no second credential and shows no drive.
        PeergosSession.publish(context, context.network, context.crypto);
        active = config.mountDrive;
        notifyRoots();
        // This is the one moment the app has a signed-in session without the WebView, which
        // is exactly what the sync adapters need, so the account is registered here rather
        // than given a credential path of its own.
        sync(config.syncCalendar, CalendarContract.AUTHORITY, SyncPermission.CALENDAR, context.username);
        sync(config.syncContacts, ContactsContract.AUTHORITY, SyncPermission.CONTACTS, context.username);
    }

    private void sync(boolean wanted, String authority, SyncPermission permission, String username) {
        if (! wanted) {
            PeergosAccount.stopSyncing(appContext, authority);
            permission.onStopped();
            return;
        }
        permission.onStarted();
        try {
            PeergosAccount.startSyncing(PeergosAccount.ensure(appContext, username), authority);
        } catch (RuntimeException e) {
            // A missing accounts permission should not take the rest of the login down with it.
            Log.w(TAG, "Could not register the Peergos account for " + authority + " sync", e);
        }
    }

    /**
     * Leaves the account, its calendars and its contacts in place. Without a session the
     * sync adapters are no-ops, so those simply stop updating; removing the account would
     * delete them from the device every time the mount was toggled.
     */
    @Override
    public void disable() {
        PeergosSession.clear();
        active = false;
        SyncPermission.CALENDAR.onStopped();
        SyncPermission.CONTACTS.onStopped();
        notifyRoots();
    }

    @Override
    public Optional<String> activeMountPoint() {
        return active ? Optional.of("Files app") : Optional.empty();
    }

    /** Calendars and contacts go through the platform's own sync account, so there is no
     *  bridge for a CalDAV or CardDAV client to point at. */
    @Override
    public boolean supportsCalendar() {
        return true;
    }

    @Override
    public boolean supportsContacts() {
        return true;
    }

    private void notifyRoots() {
        ContentResolver cr = appContext.getContentResolver();
        Uri roots = DocumentsContract.buildRootsUri(AUTHORITY);
        cr.notifyChange(roots, null);
    }
}
