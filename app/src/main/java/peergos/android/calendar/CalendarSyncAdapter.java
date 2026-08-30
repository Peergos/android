package peergos.android.calendar;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Log;

import java.util.Optional;

import peergos.android.PeergosSession;
import peergos.server.webdav.caldav.CalendarStore;
import peergos.shared.user.UserContext;

/**
 * Moves the Peergos calendars into the platform calendar provider.
 *
 * There is no HTTP and no DAV here: the adapter reads the same app-data layout the CalDAV
 * bridge serves, in process, through {@link CalendarStore}.
 *
 * The session comes from {@link PeergosSession}, which the app publishes once it has
 * signed in. When there is no session the sync is a no-op rather than an error: the user
 * has not logged in yet, or the app was evicted, and the next periodic sync after they
 * open it will do the work. Reporting a failure instead would earn us exponential backoff
 * for a state that resolves itself.
 */
public class CalendarSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "PeergosCalendar";

    public CalendarSyncAdapter(Context context) {
        super(context, true);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        Optional<UserContext> session = PeergosSession.context();
        if (session.isEmpty()) {
            Log.i(TAG, "No Peergos session; leaving the calendars as they are");
            return;
        }
        UserContext context = session.get();
        if (! account.name.equals(context.username)) {
            // The account outlived the login it was made for. Someone else is signed in,
            // and their calendars are not this account's to publish.
            Log.w(TAG, "Account " + account.name + " does not match session " + context.username);
            syncResult.stats.numAuthExceptions++;
            return;
        }
        try {
            int changes = new CalendarMirror(provider, account, new CalendarStore(context)).sync();
            syncResult.stats.numEntries += changes;
            Log.i(TAG, "Calendar sync applied " + changes + " changes");
        } catch (Exception e) {
            // Anything here is a network or provider failure, both of which are worth
            // retrying; the framework backs off on its own.
            Log.w(TAG, "Calendar sync failed", e);
            syncResult.stats.numIoExceptions++;
        }
    }
}
