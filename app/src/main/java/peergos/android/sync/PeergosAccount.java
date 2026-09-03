package peergos.android.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;

import java.util.Optional;

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
        // Calendar and contact changes are small and not urgent, so the periodic framework
        // batches these into the system's existing wakeups, which Doze treats far better
        // than a foreground service of our own.
        ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, SYNC_INTERVAL_SECONDS);
        requestSync(account, authority);
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
