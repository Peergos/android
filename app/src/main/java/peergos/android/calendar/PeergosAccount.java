package peergos.android.calendar;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.provider.CalendarContract;

import java.util.Optional;

/**
 * The system account the calendar sync adapter hangs off.
 *
 * Android only offers the per-app calendar toggle in Settings to accounts that exist in
 * the AccountManager, so a Peergos account has to be registered even though the
 * credentials do not live here: sign-in still goes through the stored MountConfig, the
 * same path the Files-app mount uses. The account is a handle, not a second credential
 * store.
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
     * Registers the account and turns calendar syncing on. Safe to call repeatedly, so the
     * app can call it on every login without tracking whether it has run before.
     */
    public static Account ensure(Context context, String username) {
        AccountManager manager = AccountManager.get(context);
        Account account = of(username);
        for (Account existing : manager.getAccountsByType(TYPE)) {
            if (existing.name.equals(username)) {
                enableSync(account);
                return account;
            }
            // A different user signed in, so the old account's calendars are not ours.
            manager.removeAccountExplicitly(existing);
        }
        manager.addAccountExplicitly(account, null, new Bundle());
        enableSync(account);
        return account;
    }

    private static void enableSync(Account account) {
        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1);
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true);
        // Calendar changes are small and not urgent, so the periodic framework batches
        // these into the system's existing wakeups, which Doze treats far better than a
        // foreground service of our own.
        ContentResolver.addPeriodicSync(account, CalendarContract.AUTHORITY,
                Bundle.EMPTY, SYNC_INTERVAL_SECONDS);
    }

    /**
     * Stop syncing without removing the account. Removing it would delete the calendars from
     * the device, so a user who turns the calendar off and on again would lose anything the
     * platform calendar app hangs off those rows; leaving it dormant costs nothing.
     */
    public static void stopSyncing(Context context) {
        for (Account account : AccountManager.get(context).getAccountsByType(TYPE)) {
            ContentResolver.removePeriodicSync(account, CalendarContract.AUTHORITY, Bundle.EMPTY);
            ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, false);
            ContentResolver.cancelSync(account, CalendarContract.AUTHORITY);
        }
    }

    /** Ask for a sync now, e.g. after the user changes something in the web UI. */
    public static void requestSync(Account account) {
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true);
        ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras);
    }
}
