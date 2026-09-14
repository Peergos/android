package peergos.android.calendar;

import android.accounts.Account;
import android.net.Uri;
import android.provider.CalendarContract;

/** Shared by the two halves of the mirror, which both write as the sync adapter. */
final class ProviderUris {

    private ProviderUris() {}

    /**
     * Writes only count as sync-adapter writes with these parameters, and only those may
     * set _SYNC_ID or clear the dirty flag without marking the row dirty again.
     */
    static Uri asSyncAdapter(Uri uri, Account account) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();
    }
}
