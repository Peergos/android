package peergos.android.contacts;

import android.accounts.Account;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;
import android.util.Log;

import java.util.Optional;

import peergos.android.PeergosSession;
import peergos.server.webdav.caldav.ContactStore;
import peergos.shared.user.UserContext;

/**
 * Moves the Peergos address books into the platform contacts provider.
 *
 * There is no HTTP and no DAV here: the adapter reads the same app-data layout the CardDAV
 * bridge serves, in process, through {@link ContactStore}.
 *
 * The session comes from {@link PeergosSession}, which the app publishes once it has
 * signed in, or that its saved mount publishes when the process starts, which a sync waits
 * for. When there is no session the sync is a no-op rather than an error: the user has not
 * logged in yet, or the mount could not sign in, and the next periodic sync after they
 * open the app will do the work. Reporting a failure instead would earn us exponential backoff
 * for a state that resolves itself.
 */
public class ContactsSyncAdapter extends AbstractThreadedSyncAdapter {

    private static final String TAG = "PeergosContacts";

    public ContactsSyncAdapter(Context context) {
        super(context, true);
    }

    @Override
    public void onPerformSync(Account account, Bundle extras, String authority,
                              ContentProviderClient provider, SyncResult syncResult) {
        // A sync is often what started the process, and the saved mount's sign-in at start is then a second or
        // two from publishing: without waiting for it, every sync that found the process stopped did nothing.
        Optional<UserContext> session;
        try {
            session = PeergosSession.awaitContext(PeergosSession.SIGN_IN_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (session.isEmpty()) {
            Log.i(TAG, "No Peergos session; leaving the contacts as they are");
            return;
        }
        UserContext context = session.get();
        if (! account.name.equals(context.username)) {
            // The account outlived the login it was made for. Someone else is signed in,
            // and their contacts are not this account's to publish.
            Log.w(TAG, "Account " + account.name + " does not match session " + context.username);
            syncResult.stats.numAuthExceptions++;
            return;
        }
        try {
            int changes = new ContactMirror(provider, account, new ContactStore(context)).sync();
            syncResult.stats.numEntries += changes;
            Log.i(TAG, "Contact sync applied " + changes + " changes");
        } catch (Exception e) {
            // Anything here is a network or provider failure, both of which are worth
            // retrying; the framework backs off on its own.
            Log.w(TAG, "Contact sync failed", e);
            syncResult.stats.numIoExceptions++;
        }
    }
}
