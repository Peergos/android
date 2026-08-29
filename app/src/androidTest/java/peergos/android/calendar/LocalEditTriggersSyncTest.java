package peergos.android.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What happens when the user edits an event in the platform calendar app, which is the
 * trigger the upload half depends on.
 */
@RunWith(AndroidJUnit4.class)
public class LocalEditTriggersSyncTest {

    private static final String USER = "androidtest-dirty-user";
    private Long calendarId;

    /** The sync adapter runs in the app process, so the provider needs the grant, not just
     *  the manifest declaration — MainActivity asks the user for it on launch. */
    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR);

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, USER)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, PeergosAccount.TYPE)
                .build();
    }

    @After
    public void cleanUp() {
        if (calendarId != null)
            context().getContentResolver().delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                    CalendarContract.Calendars._ID + "=?", new String[]{Long.toString(calendarId)});
        AccountManager manager = AccountManager.get(context());
        for (Account account : manager.getAccountsByType(PeergosAccount.TYPE))
            manager.removeAccountExplicitly(account);
    }

    @Test
    public void anAppSideEditMarksTheRowDirty() {
        Account account = PeergosAccount.ensure(context(), USER);
        ContentResolver resolver = context().getContentResolver();

        ContentValues calendar = new ContentValues();
        calendar.put(CalendarContract.Calendars._SYNC_ID, "work");
        calendar.put(CalendarContract.Calendars.ACCOUNT_NAME, account.name);
        calendar.put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type);
        calendar.put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name);
        calendar.put(CalendarContract.Calendars.NAME, "work");
        calendar.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Work");
        calendar.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_EDITOR);
        calendar.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        calendar.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC");
        Uri inserted = resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), calendar);
        assertNotNull("the provider should accept a calendar for our account", inserted);
        calendarId = ContentUris.parseId(inserted);

        // As the calendar app would: a plain write, not a sync-adapter one.
        ContentValues event = new ContentValues();
        event.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        event.put(CalendarContract.Events.TITLE, "Added on the device");
        event.put(CalendarContract.Events.DTSTART, 1710493200000L);
        event.put(CalendarContract.Events.DTEND, 1710496800000L);
        event.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        Uri eventUri = resolver.insert(CalendarContract.Events.CONTENT_URI, event);
        assertNotNull("an EDITOR calendar should accept a new event", eventUri);

        try (Cursor cursor = resolver.query(eventUri,
                new String[]{CalendarContract.Events.DIRTY, CalendarContract.Events._SYNC_ID},
                null, null, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            assertEquals("a local edit must land as dirty for the uploader to find it",
                    1, cursor.getInt(0));
            assertTrue("a brand new local event has no sync id yet", cursor.isNull(1));
        }
    }
}
