package peergos.android.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderClient;
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
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import peergos.android.AndroidPoster;
import peergos.android.PeergosSession;
import peergos.android.ScryptAndroid;
import peergos.android.sync.PeergosAccount;
import peergos.server.Main;
import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.CalendarStore;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.login.DeviceTokenStore;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.App;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.UserContext;
import peergos.shared.util.PathUtil;

/**
 * One occurrence of a series changed or deleted, in either direction, against a real
 * Peergos server. Server arguments as in {@link CalendarSyncEndToEndTest}.
 *
 * What the user sees is the Instances table, the provider's expansion of every series, so
 * that is what these assert on: an exclusion or exception that is stored but not honoured
 * there has not done its job.
 *
 * The series runs weekly on Mondays at 09:00 UTC through January 2026: the 5th, 12th, 19th
 * and 26th.
 */
@RunWith(AndroidJUnit4.class)
public class RecurrenceExceptionsEndToEndTest {

    private static final long JAN_5 = Instant.parse("2026-01-05T09:00:00Z").toEpochMilli();
    private static final long JAN_12 = Instant.parse("2026-01-12T09:00:00Z").toEpochMilli();
    private static final long JAN_19 = Instant.parse("2026-01-19T09:00:00Z").toEpochMilli();
    private static final long HOUR = 3_600_000L;

    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR);

    private static Context context;
    private static UserContext session;
    private static Account account;
    private static App calendar;
    private static int calendars;
    private ContentProviderClient provider;
    /** Each test's own calendar, so one account serves them all. */
    private String directory;

    private Uri asSyncAdapter(Uri uri) {
        return ProviderUris.asSyncAdapter(uri, account);
    }

    /**
     * One user and one account for the class. The calendar provider drops a removed
     * account's calendars on a thread of its own, some time after, so removing and adding
     * one per test leaves a trail of sweeps behind for whichever class runs next.
     */
    @BeforeClass
    public static void signUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Crypto crypto = Main.initCrypto(new ScryptAndroid());
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "localhost");
        int port = Integer.parseInt(args.getString("peergosPort", "7777"));
        HttpPoster poster = new AndroidPoster(new URL("http://" + host + ":" + port), false,
                Optional.empty(), Optional.of("Peergos-android-calendar-test"));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        NetworkAccess network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                7_000, crypto.hasher, false, DeviceTokenStore.inMemory()).join();

        String username = "androidrec" + Math.abs(new Random().nextInt() % 1_000_000);
        session = UserContext.signUp(username, "test-password-1", "", network, crypto).join();
        PeergosSession.publish(session, session.network, session.crypto);
        account = PeergosAccount.ensure(context, username);
        // The test drives CalendarMirror itself; see CalendarSyncEndToEndTest.
        android.content.ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, false);
        android.content.ContentResolver.removePeriodicSync(account, CalendarContract.AUTHORITY,
                android.os.Bundle.EMPTY);
        android.content.ContentResolver.cancelSync(account, CalendarContract.AUTHORITY);
        calendar = App.init(session, "calendar").join();
    }

    @AfterClass
    public static void removeTheAccount() {
        AccountManager manager = AccountManager.get(context);
        for (Account existing : manager.getAccountsByType(PeergosAccount.TYPE))
            manager.removeAccountExplicitly(existing);
        PeergosSession.clear();
    }

    @Before
    public void freshCalendar() throws Exception {
        provider = context.getContentResolver().acquireContentProviderClient(CalendarContract.AUTHORITY);
        assertNotNull(provider);
        directory = "work" + calendars++;
        // App.config as well as the directory: without it the store also synthesises the
        // web app's default calendar. Naming only this one drops the last test's from the phone.
        calendar.writeInternal(PathUtil.get("App.config"),
                ("{\"calendars\":[{\"name\":\"Work\",\"directory\":\"" + directory + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8), null).join();
        calendar.writeInternal(PathUtil.get(directory + "/calendar.inf"),
                "{\"name\":\"Work\"}".getBytes(StandardCharsets.UTF_8), null).join();
    }

    @After
    public void tearDown() {
        try {
            provider.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                    CalendarContract.Calendars.ACCOUNT_NAME + "=?", new String[]{account.name});
        } catch (Exception ignored) {
            // best effort; the next test's first sync drops it anyway
        }
        provider.close();
    }

    // ---- Peergos to the phone ----

    /** The web app's own shape: an EXDATE in the series' zone and the moved copy as an event
     *  of its own. Before, the phone drew the occurrence twice. */
    @Test
    public void anOccurrenceMovedInPeergosShowsOnceInItsNewPlace() throws Exception {
        put("recurring/weekly.ics", series("DTSTART;TZID=Europe/London:20260105T090000",
                "EXDATE;TZID=Europe/London:20260112T090000\r\n"));
        put("2026/1/moved.ics", single("moved", "Moved", "20260112T140000Z", "20260112T150000Z"));
        sync();
        long calendarId = onlyCalendar();
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_12 + 5 * HOUR, "Moved"),
                        at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")),
                instances(calendarId));
        assertEquals("nothing changed, nothing written", 0, sync());

        // and putting the occurrence back in Peergos puts it back here
        put("recurring/weekly.ics", series("DTSTART;TZID=Europe/London:20260105T090000", ""));
        sync();
        assertTrue(instances(calendarId).contains(at(JAN_12, "Weekly")));
    }

    @Test
    public void anAllDayOccurrenceDeletedInPeergosIsGone() throws Exception {
        put("recurring/days.ics", allDaySeries("EXDATE;VALUE=DATE:20260112\r\n"));
        sync();
        long calendarId = onlyCalendar();
        List<String> seen = instances(calendarId);
        assertEquals(seen.toString(), 3, seen.size());
        assertFalse(seen.toString(), seen.contains(at(Instant.parse("2026-01-12T00:00:00Z").toEpochMilli(), "Days")));
    }

    /** The shape macOS Calendar and other CalDAV clients write: an override in the same file. */
    @Test
    public void anOverrideInTheSeriesFileIsAnExceptionOnThePhone() throws Exception {
        put("recurring/weekly.ics", withOverride(series("DTSTART:20260105T090000Z", "")));
        sync();
        long calendarId = onlyCalendar();
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_12 + 5 * HOUR, "Moved by CalDAV"),
                        at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")),
                instances(calendarId));
        assertEquals("nothing changed, nothing written", 0, sync());
        assertEquals("nor did the second pass lose the exception: " + rows(calendarId),
                at(JAN_12 + 5 * HOUR, "Moved by CalDAV"), instances(calendarId).get(1));

        // the override removed in Peergos goes from the phone too
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", ""));
        sync();
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_12, "Weekly"),
                at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")), instances(calendarId));
        assertEquals("only the series is left: " + rows(calendarId), 1, rows(calendarId).size());
    }

    // ---- the phone to Peergos ----

    /** What AOSP's calendar, and Etar after it, write for "delete this occurrence": a
     *  cancelled exception row naming the series by its sync id. Before, this made a new
     *  event in Peergos and the occurrence came back. */
    @Test
    public void anOccurrenceDeletedOnThePhoneIsExcludedInPeergos() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", ""));
        sync();
        long calendarId = onlyCalendar();
        cancelLikeAosp(calendarId, "weekly.ics", JAN_12, false);

        sync();
        assertEquals("no new event: " + names(), List.of("weekly.ics"), names());
        String ics = read("weekly.ics");
        assertTrue(ics, ics.contains("EXDATE:20260112T090000Z"));
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")),
                instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    @Test
    public void anAllDayOccurrenceDeletedOnThePhoneIsExcludedInPeergos() throws Exception {
        put("recurring/days.ics", allDaySeries(""));
        sync();
        long calendarId = onlyCalendar();
        long jan19 = Instant.parse("2026-01-19T00:00:00Z").toEpochMilli();
        cancelLikeAosp(calendarId, "days.ics", jan19, true);

        sync();
        String ics = read("days.ics");
        assertTrue(ics, ics.contains("EXDATE;VALUE=DATE:20260119"));
        List<String> seen = instances(calendarId);
        assertEquals(seen.toString(), 3, seen.size());
        assertFalse(seen.toString(), seen.contains(at(jan19, "Days")));
    }

    /** "Change only this occurrence" through the provider's exception URI, which fills in
     *  the rest of the row from the series. */
    @Test
    public void anOccurrenceChangedOnThePhoneBecomesTheWebAppsShape() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", ""));
        sync();
        long calendarId = onlyCalendar();
        ContentValues change = new ContentValues();
        change.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, JAN_12);
        change.put(CalendarContract.Events.TITLE, "Moved on the phone");
        change.put(CalendarContract.Events.DTSTART, JAN_12 + 5 * HOUR);
        // the provider takes the length of an exception as a duration, and works out DTEND
        change.put(CalendarContract.Events.DURATION, "PT60M");
        assertNotNull(context.getContentResolver().insert(Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_EXCEPTION_URI, Long.toString(seriesRow(calendarId, "weekly.ics"))), change));

        sync();
        List<String> names = names();
        assertEquals(names.toString(), 2, names.size());
        String series = read("weekly.ics");
        assertTrue(series, series.contains("EXDATE:20260112T090000Z"));
        assertTrue(series, series.contains("RRULE:FREQ=WEEKLY;COUNT=4"));
        String moved = read(names.stream().filter(n -> ! n.equals("weekly.ics")).findFirst().orElseThrow());
        assertTrue(moved, moved.contains("SUMMARY:Moved on the phone\r\n"));
        assertTrue(moved, moved.contains("DTSTART:20260112T140000Z"));
        assertTrue(moved, moved.contains("DTEND:20260112T150000Z"));
        assertFalse(moved, moved.contains("RRULE"));
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_12 + 5 * HOUR, "Moved on the phone"),
                at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")), instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    /** An exclusion goes into whatever version of the series Peergos holds, so an edit made
     *  there meanwhile is kept rather than raced. */
    @Test
    public void anOccurrenceDeletedOnThePhoneSurvivesASeriesEditInPeergos() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", ""));
        sync();
        long calendarId = onlyCalendar();
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", "LOCATION:Room 2\r\n"));
        cancelLikeAosp(calendarId, "weekly.ics", JAN_12, false);

        sync();
        assertEquals(List.of("weekly.ics"), names());
        String ics = read("weekly.ics");
        assertTrue(ics, ics.contains("LOCATION:Room 2"));
        assertTrue(ics, ics.contains("EXDATE:20260112T090000Z"));
        assertEquals(3, instances(calendarId).size());
    }

    /** A calendar app may exclude by writing the series' own EXDATE column instead. It saves
     *  the whole recurrence with it: the provider re-expands a series only from the columns
     *  an update carries, so an EXDATE alone would not take an occurrence off the phone. */
    @Test
    public void anExclusionWrittenOnTheSeriesRowReachesPeergos() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", "EXDATE:20260112T090000Z\r\n"));
        sync();
        long calendarId = onlyCalendar();
        ContentValues values = recurrence();
        values.put(CalendarContract.Events.EXDATE, "20260112T090000Z,20260119T090000Z");
        assertEquals(1, context.getContentResolver().update(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, seriesRow(calendarId, "weekly.ics")), values, null, null));

        sync();
        String ics = read("weekly.ics");
        assertTrue(ics, ics.contains("EXDATE:20260112T090000Z,20260119T090000Z"));
        assertEquals("one EXDATE line, not the old one and the new: " + ics, 1, ics.split("EXDATE").length - 1);
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")), instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    @Test
    public void anOverrideFromCalDavChangedOnThePhone() throws Exception {
        put("recurring/weekly.ics", withOverride(series("DTSTART:20260105T090000Z", "")));
        sync();
        long calendarId = onlyCalendar();
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.TITLE, "Changed again");
        assertEquals(1, context.getContentResolver().update(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, overrideRow(calendarId)), values, null, null));

        sync();
        String series = read("weekly.ics");
        assertFalse("the override has become an event of its own: " + series, series.contains("RECURRENCE-ID"));
        assertTrue(series, series.contains("EXDATE:20260112T090000Z"));
        assertEquals(names().toString(), 2, names().size());
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_12 + 5 * HOUR, "Changed again"),
                at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")), instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    @Test
    public void anOverrideFromCalDavDeletedOnThePhone() throws Exception {
        put("recurring/weekly.ics", withOverride(series("DTSTART:20260105T090000Z", "")));
        sync();
        long calendarId = onlyCalendar();
        assertEquals(1, context.getContentResolver().delete(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, overrideRow(calendarId)), null, null));

        sync();
        String series = read("weekly.ics");
        assertFalse(series, series.contains("RECURRENCE-ID"));
        assertTrue(series, series.contains("EXDATE:20260112T090000Z"));
        assertEquals(List.of("weekly.ics"), names());
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_19, "Weekly"), at(JAN_19 + 7 * 24 * HOUR, "Weekly")),
                instances(calendarId));
    }

    /** The series and its exception both made on the phone between two syncs: the exception
     *  names the series only by row id, as it has no sync id yet. */
    @Test
    public void aSeriesAndItsExceptionMadeOnThePhoneTogether() throws Exception {
        sync();
        long calendarId = onlyCalendar();
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, "Phone series");
        values.put(CalendarContract.Events.DTSTART, JAN_5);
        values.put(CalendarContract.Events.DURATION, "PT60M");
        values.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=4");
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        long seriesId = ContentUris.parseId(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));
        ContentValues cancel = new ContentValues();
        cancel.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, JAN_12);
        cancel.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED);
        assertNotNull(context.getContentResolver().insert(Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_EXCEPTION_URI, Long.toString(seriesId)), cancel));

        sync();
        List<String> names = names();
        assertEquals(names.toString(), 1, names.size());
        String ics = read(names.get(0));
        assertTrue(ics, ics.contains("RRULE:FREQ=WEEKLY;COUNT=4"));
        assertTrue(ics, ics.contains("EXDATE:20260112T090000Z"));
        assertEquals(List.of(at(JAN_5, "Phone series"), at(JAN_19, "Phone series"),
                at(JAN_19 + 7 * 24 * HOUR, "Phone series")), instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    /** "This and following" is the provider splitting the series in two, which was already
     *  two ordinary edits: the old series cut short, and a new one. */
    @Test
    public void thisAndFollowingChangedOnThePhone() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", ""));
        sync();
        long calendarId = onlyCalendar();
        ContentValues change = new ContentValues();
        change.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, JAN_19);
        change.put(CalendarContract.Events.TITLE, "Later");
        change.put(CalendarContract.Events.DTSTART, JAN_19 + HOUR);
        change.put(CalendarContract.Events.DURATION, "PT60M");
        change.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=2");
        change.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        assertNotNull(context.getContentResolver().insert(Uri.withAppendedPath(
                CalendarContract.Events.CONTENT_EXCEPTION_URI, Long.toString(seriesRow(calendarId, "weekly.ics"))), change));

        sync();
        List<String> names = names();
        assertEquals(names + " " + rows(calendarId), 2, names.size());
        String before = read("weekly.ics");
        // the provider cuts a counted series short by recounting it, an open one with UNTIL
        assertTrue("the original series is cut short: " + before, before.contains("RRULE:FREQ=WEEKLY;COUNT=2"));
        assertTrue(before, before.contains("SUMMARY:Weekly\r\n"));
        String after = read(names.stream().filter(n -> ! n.equals("weekly.ics")).findFirst().orElseThrow());
        assertTrue(after, after.contains("SUMMARY:Later\r\n"));
        assertTrue(after, after.contains("RRULE:FREQ=WEEKLY;COUNT=2"));
        assertEquals(List.of(at(JAN_5, "Weekly"), at(JAN_12, "Weekly"),
                at(JAN_19 + HOUR, "Later"), at(JAN_19 + 7 * 24 * HOUR + HOUR, "Later")), instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    /** A series an earlier version of the app mirrored has no EXDATE column. Editing it on the
     *  phone must not take that empty column for the truth and wipe Peergos' exclusions. */
    @Test
    public void aSeriesMirroredBeforeExclusionsKeepsThemWhenEditedOnThePhone() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", "EXDATE:20260112T090000Z\r\n"));
        sync();
        long calendarId = onlyCalendar();
        long seriesId = seriesRow(calendarId, "weekly.ics");
        mirroredByThePreviousVersion(seriesId);
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.TITLE, "Weekly, renamed");
        assertEquals(1, context.getContentResolver().update(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, seriesId), values, null, null));

        sync();
        String ics = read("weekly.ics");
        assertTrue(ics, ics.contains("SUMMARY:Weekly\\, renamed"));
        assertTrue(ics, ics.contains("EXDATE:20260112T090000Z"));
        assertEquals("and the phone has it again", List.of(at(JAN_5, "Weekly, renamed"),
                at(JAN_19, "Weekly, renamed"), at(JAN_19 + 7 * 24 * HOUR, "Weekly, renamed")), instances(calendarId));
        assertEquals("nothing left to push or pull", 0, sync());
    }

    /** The same series untouched on the phone is read again once, gaining its exclusions. */
    @Test
    public void aSeriesMirroredBeforeExclusionsIsReadAgain() throws Exception {
        put("recurring/weekly.ics", series("DTSTART:20260105T090000Z", "EXDATE:20260112T090000Z\r\n"));
        sync();
        long calendarId = onlyCalendar();
        mirroredByThePreviousVersion(seriesRow(calendarId, "weekly.ics"));
        assertEquals(4, instances(calendarId).size());

        assertEquals(1, sync());
        assertEquals(3, instances(calendarId).size());
        assertEquals(0, sync());
    }

    // ---- helpers ----

    /** The columns the series is saved with, as a calendar app's save sends them. */
    private static ContentValues recurrence() {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.DTSTART, JAN_5);
        values.put(CalendarContract.Events.DURATION, "PT60M");
        values.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=4");
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        return values;
    }

    /** The series row as the previous version left it: no exclusions, no format mark. */
    private void mirroredByThePreviousVersion(long seriesId) throws Exception {
        ContentValues old = recurrence();
        old.putNull(CalendarContract.Events.EXDATE);
        old.putNull(CalendarContract.Events.SYNC_DATA2);
        provider.update(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, seriesId)),
                old, null, null);
    }

    private static String series(String start, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Peergos//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:weekly\r\nDTSTAMP:20251201T000000Z\r\nSUMMARY:Weekly\r\n"
                + start + "\r\nDTEND:20260105T100000Z\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\n" + extra
                + "X-OWNER:someone\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private static String allDaySeries(String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Peergos//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:days\r\nDTSTAMP:20251201T000000Z\r\nSUMMARY:Days\r\n"
                + "DTSTART;VALUE=DATE:20260105\r\nDTEND;VALUE=DATE:20260106\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\n" + extra
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private static String single(String uid, String summary, String start, String end) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Peergos//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20251201T000000Z\r\nSUMMARY:" + summary + "\r\n"
                + "DTSTART:" + start + "\r\nDTEND:" + end + "\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    /** The series with its 12 January occurrence moved to 14:00, as a CalDAV client stores it. */
    private static String withOverride(String series) {
        return series.replace("END:VCALENDAR\r\n",
                "BEGIN:VEVENT\r\nUID:weekly\r\nDTSTAMP:20251201T000000Z\r\nRECURRENCE-ID:20260112T090000Z\r\n"
                        + "SUMMARY:Moved by CalDAV\r\nDTSTART:20260112T140000Z\r\nDTEND:20260112T150000Z\r\n"
                        + "END:VEVENT\r\nEND:VCALENDAR\r\n");
    }

    private void put(String path, String ics) {
        calendar.writeInternal(PathUtil.get(directory + "/" + path), ics.getBytes(StandardCharsets.UTF_8), null).join();
    }

    private CalendarStore store() {
        return new CalendarStore(session);
    }

    private int sync() throws Exception {
        return new CalendarMirror(provider, account, store()).sync();
    }

    private List<String> names() throws Exception {
        return store().listObjects(directory).stream().map(o -> o.name).sorted().collect(Collectors.toList());
    }

    private String read(String name) throws Exception {
        AppDataStore.ObjectRef ref = store().getObject(directory, name).orElseThrow();
        return new String(store().read(ref), StandardCharsets.UTF_8);
    }

    /** As AOSP's DeleteEventHelper does it. */
    private void cancelLikeAosp(long calendarId, String series, long instance, boolean allDay) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, "whatever");
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        values.put(CalendarContract.Events.ORIGINAL_ALL_DAY, allDay ? 1 : 0);
        values.put(CalendarContract.Events.ORIGINAL_SYNC_ID, series);
        values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instance);
        values.put(CalendarContract.Events.DTSTART, instance);
        values.put(CalendarContract.Events.DTEND, instance + (allDay ? 24 * HOUR : HOUR));
        values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED);
        assertNotNull(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));
    }

    private static String at(long begin, String title) {
        return Instant.ofEpochMilli(begin) + " " + title;
    }

    /** What a calendar app would draw for January 2026, cancelled occurrences left out as
     *  they are by every calendar app. */
    private List<String> instances(long calendarId) {
        Uri.Builder range = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(range, Instant.parse("2026-01-01T00:00:00Z").toEpochMilli());
        ContentUris.appendId(range, Instant.parse("2026-02-01T00:00:00Z").toEpochMilli());
        List<String> out = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(range.build(),
                new String[]{CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE},
                CalendarContract.Instances.CALENDAR_ID + "=? AND (" + CalendarContract.Instances.STATUS + " IS NULL OR "
                        + CalendarContract.Instances.STATUS + "!=" + CalendarContract.Events.STATUS_CANCELED + ")",
                new String[]{Long.toString(calendarId)},
                CalendarContract.Instances.BEGIN)) {
            while (cursor != null && cursor.moveToNext())
                out.add(at(cursor.getLong(0), cursor.getString(1)));
        }
        return out;
    }

    private List<String> rows(long calendarId) throws Exception {
        List<String> out = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._ID, CalendarContract.Events._SYNC_ID,
                        CalendarContract.Events.ORIGINAL_SYNC_ID, CalendarContract.Events.DIRTY,
                        CalendarContract.Events.DELETED, CalendarContract.Events.TITLE},
                CalendarContract.Events.CALENDAR_ID + "=?", new String[]{Long.toString(calendarId)}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                StringBuilder row = new StringBuilder("[");
                for (int i = 0; i < cursor.getColumnCount(); i++)
                    row.append(cursor.getColumnName(i)).append('=').append(cursor.getString(i)).append(' ');
                out.add(row.append(']').toString());
            }
        }
        return out;
    }

    private long seriesRow(long calendarId, String name) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._ID},
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=?",
                new String[]{Long.toString(calendarId), name}, null)) {
            assertTrue("expected " + name + " on the device", cursor != null && cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private long overrideRow(long calendarId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._ID},
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events.ORIGINAL_SYNC_ID + " IS NOT NULL",
                new String[]{Long.toString(calendarId)}, null)) {
            assertTrue("expected the override on the device: " + rows(calendarId), cursor != null && cursor.moveToFirst());
            return cursor.getLong(0);
        }
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
}
