package peergos.android.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;

import peergos.android.AndroidPoster;
import peergos.android.PeergosSession;
import peergos.android.ScryptAndroid;
import peergos.server.Main;
import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.CalendarStore;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.App;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.UserContext;
import peergos.shared.util.PathUtil;

/**
 * The sync adapter against a real Peergos server, which is the only way to know that the
 * layout the mirror reads is the layout the calendar app writes.
 *
 * Host and port come from instrumentation args, as in {@link peergos.android.SyncEndToEndTest}.
 * The default is a local server on 7777 (web-ui's {@code ant run}) reached over
 * {@code adb reverse tcp:7777 tcp:7777}, which has to be addressed as localhost: a server
 * started that way only accepts a Host header naming itself. CI instead starts the server
 * with {@code -listen-host 0.0.0.0}, which makes SubdomainHandler accept any IPv4 Host, and
 * passes peergosHost=10.0.2.2 peergosPort=8000 to reach it as the host of the emulator.
 */
@RunWith(AndroidJUnit4.class)
public class CalendarSyncEndToEndTest {

    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR);

    private Context context;
    private UserContext session;
    private Account account;
    private ContentProviderClient provider;
    private String username;

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();
    }

    @Before
    public void signUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Crypto crypto = Main.initCrypto(new ScryptAndroid());
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "localhost");
        int port = Integer.parseInt(args.getString("peergosPort", "7777"));
        // false = not a public server: AndroidPoster then turns every GET into a POST with
        // an empty body, which is what a localhost instance accepts — HttpUtil.allowedQuery
        // rejects GETs unless the server is public. The app passes true because peergos.net
        // is public.
        HttpPoster poster = new AndroidPoster(new URL("http://" + host + ":" + port), false,
                Optional.empty(), Optional.of("Peergos-android-calendar-test"));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        NetworkAccess network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                7_000, crypto.hasher, false).join();

        username = "androidcal" + Math.abs(new Random().nextInt() % 1_000_000);
        session = UserContext.signUp(username, "test-password-1", "", network, crypto).join();
        PeergosSession.publish(session, session.network, session.crypto);
        account = PeergosAccount.ensure(context, username);
        // The test drives CalendarMirror directly, so stop the framework scheduling its own
        // pass alongside: two overlapping passes are exactly what the lock in the mirror is
        // there to prevent, and letting one run here would make the assertions racy.
        android.content.ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, false);
        android.content.ContentResolver.removePeriodicSync(account, CalendarContract.AUTHORITY,
                android.os.Bundle.EMPTY);
        android.content.ContentResolver.cancelSync(account, CalendarContract.AUTHORITY);
        provider = context.getContentResolver()
                .acquireContentProviderClient(CalendarContract.AUTHORITY);
        assertNotNull(provider);
    }

    @After
    public void tearDown() {
        if (account != null) {
            try {
                context.getContentResolver().delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                        null, null);
            } catch (RuntimeException ignored) {
                // best effort; the account removal below takes the calendars with it
            }
            AccountManager manager = AccountManager.get(context);
            for (Account existing : manager.getAccountsByType(PeergosAccount.TYPE))
                manager.removeAccountExplicitly(existing);
        }
        if (provider != null)
            provider.close();
        PeergosSession.clear();
    }

    private CalendarStore store() {
        return new CalendarStore(session);
    }

    private static String event(String uid, String summary, String start, String end, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Peergos//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\nSUMMARY:" + summary + "\r\n"
                + "DTSTART:" + start + "\r\nDTEND:" + end + "\r\n" + extra
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    @Test
    public void peergosCalendarsAppearOnTheDevice() throws Exception {
        App calendar = App.init(session, "calendar").join();
        calendar.writeInternal(PathUtil.get("App.config"),
                ("{\"calendars\":[{\"name\":\"Work\",\"directory\":\"work\",\"color\":\"#ff6600\"}]}")
                        .getBytes(StandardCharsets.UTF_8), null).join();
        calendar.writeInternal(PathUtil.get("work/calendar.inf"),
                "{\"name\":\"Work\",\"color\":\"#ff6600\"}".getBytes(StandardCharsets.UTF_8), null).join();
        calendar.writeInternal(PathUtil.get("work/2024/3/standup.ics"),
                event("standup", "Daily standup", "20240315T090000Z", "20240315T100000Z", "")
                        .getBytes(StandardCharsets.UTF_8), null).join();
        calendar.writeInternal(PathUtil.get("work/recurring/weekly.ics"),
                event("weekly", "Weekly sync", "20240101T090000Z", "20240101T100000Z",
                        "RRULE:FREQ=WEEKLY\r\n").getBytes(StandardCharsets.UTF_8), null).join();

        int changes = new CalendarMirror(provider, account, store()).sync();
        assertTrue("the mirror should have written something, got " + changes, changes >= 2);

        long calendarId = onlyCalendar();
        assertEquals("after one pass: " + describe(calendarId), 1, countOf(calendarId, "weekly.ics"));
        assertEquals("Daily standup", titleOf(calendarId, "standup.ics"));
        assertEquals("Weekly sync", titleOf(calendarId, "weekly.ics"));

        // A second pass with nothing changed must be a no-op, which is what the ETag check
        // buys: otherwise every sync rewrites every event.
        assertEquals("an unchanged calendar should cost no writes",
                0, new CalendarMirror(provider, account, store()).sync());
        assertEquals("after two passes: " + describe(calendarId), 1, countOf(calendarId, "weekly.ics"));

        // A deletion in Peergos removes it from the device.
        AppDataStore.ObjectRef standup = store().getObject("work", "standup.ics").orElseThrow();
        store().deleteObject("work", standup);
        new CalendarMirror(provider, account, store()).sync();
        assertEquals("deleted in Peergos, so gone from the device", 0, countOf(calendarId, "standup.ics"));
        assertEquals("the other event survives: " + describe(calendarId),
                1, countOf(calendarId, "weekly.ics"));
    }

    @Test
    public void aDeviceEditReachesPeergos() throws Exception {
        App calendar = App.init(session, "calendar").join();
        // App.config as well as the directory: without it the store also synthesises the
        // web app's default calendar, and the event would land in whichever came first.
        calendar.writeInternal(PathUtil.get("App.config"),
                "{\"calendars\":[{\"name\":\"Work\",\"directory\":\"work\"}]}"
                        .getBytes(StandardCharsets.UTF_8), null).join();
        calendar.writeInternal(PathUtil.get("work/calendar.inf"),
                "{\"name\":\"Work\"}".getBytes(StandardCharsets.UTF_8), null).join();
        new CalendarMirror(provider, account, store()).sync();
        assertEquals("one calendar, not the synthesised default as well", 1, calendarCount());
        long calendarId = onlyCalendar();

        // As the platform calendar app would: a plain write, so the row lands dirty.
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, "Created on the phone");
        values.put(CalendarContract.Events.DESCRIPTION, "Notes; with, punctuation");
        values.put(CalendarContract.Events.DTSTART, 1710493200000L);
        values.put(CalendarContract.Events.DTEND, 1710496800000L);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        assertNotNull(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));

        new CalendarMirror(provider, account, store()).sync();

        var objects = store().listObjects("work");
        assertEquals("exactly one event should have reached Peergos", 1, objects.size());
        String ics = new String(store().read(objects.get(0)), StandardCharsets.UTF_8);
        assertTrue("summary: " + ics, ics.contains("SUMMARY:Created on the phone"));
        assertTrue("the web app needs DTEND: " + ics, ics.contains("DTEND:"));
        String backslash = "\\";
        assertTrue("text should be escaped: " + ics,
                ics.contains("Notes" + backslash + "; with" + backslash + ", punctuation"));
        assertTrue("filed under the event's own UTC month",
                objects.get(0).shard.equals("2024/3"));

        // And the row is no longer dirty, so it is not uploaded again.
        assertEquals("a second pass should push nothing",
                0, new CalendarMirror(provider, account, store()).sync());
    }

    private long onlyCalendar() throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.ACCOUNT_NAME + "=?", new String[]{account.name}, null)) {
            assertNotNull(cursor);
            assertTrue("expected a calendar on the device", cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private String titleOf(long calendarId, String syncId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events.TITLE},
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=?",
                new String[]{Long.toString(calendarId), syncId}, null)) {
            assertNotNull(cursor);
            assertTrue("expected " + syncId + " on the device", cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    private String describe(long calendarId) throws Exception {
        StringBuilder out = new StringBuilder();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._ID, CalendarContract.Events._SYNC_ID,
                        CalendarContract.Events.DELETED, CalendarContract.Events.TITLE},
                CalendarContract.Events.CALENDAR_ID + "=?",
                new String[]{Long.toString(calendarId)}, null)) {
            while (cursor != null && cursor.moveToNext())
                out.append("[id=").append(cursor.getLong(0)).append(" sync=").append(cursor.getString(1))
                        .append(" del=").append(cursor.getInt(2)).append("] ");
        }
        out.append(" calendars=").append(calendarCount());
        return out.toString();
    }

    private int calendarCount() throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.ACCOUNT_NAME + "=?", new String[]{account.name}, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    private void dump(long calendarId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._ID, CalendarContract.Events._SYNC_ID,
                        CalendarContract.Events.DIRTY, CalendarContract.Events.DELETED,
                        CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.TITLE,
                        CalendarContract.Events.RRULE},
                CalendarContract.Events.CALENDAR_ID + "=?",
                new String[]{Long.toString(calendarId)}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                StringBuilder row = new StringBuilder("ROW");
                for (int i = 0; i < cursor.getColumnCount(); i++)
                    row.append(" | ").append(cursor.getColumnName(i)).append("=")
                            .append(cursor.isNull(i) ? "null" : cursor.getString(i));
                android.util.Log.w("PeergosCalendarTest", row.toString());
            }
        }
    }

    private int countOf(long calendarId, String syncId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._ID},
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=?",
                new String[]{Long.toString(calendarId), syncId}, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }
}
