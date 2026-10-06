package peergos.android.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The system account the sync adapters hang off.
 *
 * Android only offers the per-app calendar and contacts toggles in Settings to accounts
 * that exist in the AccountManager, so a Peergos account has to be registered even though
 * the credentials do not live here: sign-in still goes through the stored MountConfig, the
 * same path the Files-app mount uses. The account is a handle, not a second credential
 * store.
 *
 * One account carries every authority, because the user has one Peergos login: the
 * calendar and the address book are two things it syncs, not two accounts. Which of them
 * is running is therefore per authority, which is what these methods take.
 */
public final class PeergosAccount {

    public static final String TYPE = "peergos.android.account";
    private static final long SYNC_INTERVAL_SECONDS = 30 * 60;

    private PeergosAccount() {}

    public static Account of(String username) {
        return new Account(username, TYPE);
    }

    /** The Peergos account registered on the device, if the user has one. */
    public static Optional<Account> existing(Context context) {
        Account[] accounts = AccountManager.get(context).getAccountsByType(TYPE);
        return accounts.length == 0 ? Optional.empty() : Optional.of(accounts[0]);
    }

    /**
     * Registers the account, leaving every authority as it was. Safe to call repeatedly, so
     * the app can call it on every login without tracking whether it has run before.
     */
    public static Account ensure(Context context, String username) {
        AccountManager manager = AccountManager.get(context);
        Account account = of(username);
        for (Account existing : manager.getAccountsByType(TYPE)) {
            if (existing.name.equals(username))
                return account;
            // A different user signed in, so the old account's data is not ours.
            manager.removeAccountExplicitly(existing);
        }
        manager.addAccountExplicitly(account, null, new Bundle());
        return account;
    }

    /** Turns syncing on for one authority and asks for a first pass. */
    public static void startSyncing(Account account, String authority) {
        ContentResolver.setIsSyncable(account, authority, 1);
        ContentResolver.setSyncAutomatically(account, authority, true);
        schedule(account, authority, SCHEDULE_ATTEMPTS);
    }

    /** How often, and for how long, a periodic sync that has not taken is asked for again. */
    private static final long SCHEDULE_RETRY_MS = 500;
    private static final int SCHEDULE_ATTEMPTS = 60;
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "PeergosSyncSchedule");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Android's sync manager hears of a new account from a broadcast, a moment after it is
     * added, and until then drops a periodic sync or a sync request for it, for good, as being
     * for an account that doesn't exist. Asked for straight after {@link #ensure} added the
     * account, as it is on signing in, nothing would then sync by itself until the app was next
     * started. So the periodic sync is asked for again until it is there, and the first pass
     * only then. Checked off the calling thread, which can be the main one.
     */
    private static void schedule(Account account, String authority, int attemptsLeft) {
        // Calendar and contact changes are small and not urgent, so the periodic framework
        // batches these into the system's existing wakeups, which Doze treats far better
        // than a foreground service of our own.
        ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, SYNC_INTERVAL_SECONDS);
        scheduler.schedule(() -> {
            // turned off again meanwhile
            if (! ContentResolver.getSyncAutomatically(account, authority))
                return;
            if (! ContentResolver.getPeriodicSyncs(account, authority).isEmpty())
                requestSync(account, authority);
            else if (attemptsLeft > 1)
                schedule(account, authority, attemptsLeft - 1);
        }, SCHEDULE_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop syncing one authority without removing the account. Removing it would delete the
     * calendars and contacts from the device, so a user who turns one off and on again would
     * lose anything the platform apps hang off those rows; leaving it dormant costs nothing.
     */
    public static void stopSyncing(Context context, String authority) {
        for (Account account : AccountManager.get(context).getAccountsByType(TYPE)) {
            ContentResolver.removePeriodicSync(account, authority, Bundle.EMPTY);
            ContentResolver.setSyncAutomatically(account, authority, false);
            ContentResolver.cancelSync(account, authority);
        }
    }

    /** Ask for a sync now, e.g. after the user changes something in the web UI. */
    public static void requestSync(Account account, String authority) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(account, authority, extras);
    }
}
