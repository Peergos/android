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
import java.time.LocalDateTime;
import java.time.ZoneId;
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
 * The fields beyond title and time, in both directions, against a real Peergos server:
 * zones however a file names them, floating times, attendees, dates added to a rule,
 * colour, availability, privacy and status. Server arguments as in
 * {@link CalendarSyncEndToEndTest}.
 *
 * The phone's side is driven the way a calendar app drives it, through the provider without
 * the sync adapter flag, so every change is marked dirty exactly as a real edit would be.
 */
@RunWith(AndroidJUnit4.class)
public class CalendarFieldsEndToEndTest {

    private static final long HOUR = 3_600_000L;
    private static final String BERLIN_RULES = "BEGIN:STANDARD\r\nDTSTART:16010101T030000\r\nTZOFFSETFROM:+0200\r\n"
            + "TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=10\r\nEND:STANDARD\r\n"
            + "BEGIN:DAYLIGHT\r\nDTSTART:16010101T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\n"
            + "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=3\r\nEND:DAYLIGHT\r\n";
    private static final String OUTLOOK_ZONE = "(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna";

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

        String username = "androidfld" + Math.abs(new Random().nextInt() % 1_000_000);
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

    // ---- zones ----

    @Test
    public void aWindowsZoneNameIsPlacedOnThePhone() throws Exception {
        put("2026/7/w.ics", event("w", "DTSTART;TZID=W. Europe Standard Time:20260715T090000\r\n"
                + "DTEND;TZID=W. Europe Standard Time:20260715T100000\r\n"));
        sync();
        ContentValues row = row("w.ics");
        assertEquals(Long.valueOf(at("2026-07-15T07:00:00Z")), row.getAsLong(CalendarContract.Events.DTSTART));
        assertEquals("Europe/Berlin", row.getAsString(CalendarContract.Events.EVENT_TIMEZONE));
    }

    /** As Outlook writes one: a zone of its own naming that only the file defines. A series
     *  in it keeps its wall clock across the change to summer time on 29 March. */
    @Test
    public void aZoneOnlyTheFileDefinesIsPlacedOnThePhone() throws Exception {
        put("recurring/o.ics", outlookSeries("FREQ=WEEKLY;COUNT=2", "20260323T090000", "20260323T100000", ""));
        sync();
        long calendarId = onlyCalendar();
        assertEquals("Europe/Amsterdam", row("o.ics").getAsString(CalendarContract.Events.EVENT_TIMEZONE));
        assertEquals(List.of("2026-03-23T08:00:00Z Weekly", "2026-03-30T07:00:00Z Weekly"),
                instances(calendarId, "2026-03-01T00:00:00Z", "2026-05-01T00:00:00Z"));
    }

    @Test
    public void aFloatingTimeIsTheSameWallClockOnThePhone() throws Exception {
        put("2026/7/f.ics", event("f", "DTSTART:20260715T090000\r\nDTEND:20260715T100000\r\n"));
        sync();
        ContentValues row = row("f.ics");
        ZoneId here = ZoneId.systemDefault();
        assertEquals(Long.valueOf(LocalDateTime.of(2026, 7, 15, 9, 0).atZone(here).toInstant().toEpochMilli()),
                row.getAsLong(CalendarContract.Events.DTSTART));
        assertEquals(here.getId(), row.getAsString(CalendarContract.Events.EVENT_TIMEZONE));
    }

    /** New York changes to summer time on 8 March, between the two occurrences. */
    @Test
    public void aSeriesMadeOnThePhoneKeepsItsZone() throws Exception {
        sync();
        long calendarId = onlyCalendar();
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, "Stand up");
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "America/New_York");
        values.put(CalendarContract.Events.DTSTART, at("2026-03-02T14:00:00Z"));
        values.put(CalendarContract.Events.DURATION, "PT1H");
        values.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=2");
        assertNotNull(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));
        sync();

        String file = read(onlyName());
        assertTrue(file, file.contains("DTSTART;TZID=America/New_York:20260302T090000\r\n"));
        assertTrue(file, file.contains("DTEND;TZID=America/New_York:20260302T100000\r\n"));
        assertTrue("defines the zone it names: " + file, file.contains("BEGIN:VTIMEZONE\r\nTZID:America/New_York\r\n"));
        assertTrue(file, file.contains("TZOFFSETFROM:-0500\r\nTZOFFSETTO:-0400"));
        assertEquals(List.of("2026-03-02T14:00:00Z Stand up", "2026-03-09T13:00:00Z Stand up"),
                instances(calendarId, "2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z"));
        assertEquals(0, sync());
    }

    @Test
    public void aRenameOnThePhoneKeepsAWindowsZone() throws Exception {
        String original = event("w", "DTSTART;TZID=W. Europe Standard Time:20260715T090000\r\n"
                + "DTEND;TZID=W. Europe Standard Time:20260715T100000\r\n");
        put("2026/7/w.ics", original);
        sync();
        edit("w.ics", v -> v.put(CalendarContract.Events.TITLE, "Renamed"));
        sync();
        String file = read("w.ics");
        assertTrue(file, file.contains("SUMMARY:Renamed"));
        assertTrue(file, file.contains("DTSTART;TZID=W. Europe Standard Time:20260715T090000\r\n"));
        assertTrue(file, file.contains("DTEND;TZID=W. Europe Standard Time:20260715T100000\r\n"));
        assertFalse(file, file.contains("VTIMEZONE"));
        assertEquals(0, sync());
    }

    /** Moved an hour later: the file keeps its own zone, quoted as its name needs. */
    @Test
    public void aMoveOnThePhoneKeepsAZoneOnlyTheFileDefines() throws Exception {
        put("recurring/o.ics", outlookSeries("FREQ=WEEKLY;COUNT=2", "20260323T090000", "20260323T100000", ""));
        sync();
        long calendarId = onlyCalendar();
        edit("o.ics", v -> {
            v.put(CalendarContract.Events.DTSTART, at("2026-03-23T09:00:00Z"));
            v.put(CalendarContract.Events.DURATION, "PT1H");
            v.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=2");
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "Europe/Amsterdam");
        });
        sync();
        String file = read("o.ics");
        assertTrue(file, file.contains("DTSTART;TZID=\"" + OUTLOOK_ZONE + "\":20260323T100000\r\n"));
        assertTrue(file, file.contains("DTEND;TZID=\"" + OUTLOOK_ZONE + "\":20260323T110000\r\n"));
        assertFalse(file, file.contains("TZID:Europe/Amsterdam"));
        assertEquals(List.of("2026-03-23T09:00:00Z Weekly", "2026-03-30T08:00:00Z Weekly"),
                instances(calendarId, "2026-03-01T00:00:00Z", "2026-05-01T00:00:00Z"));
        assertEquals(0, sync());
    }

    @Test
    public void aFloatingEventMovedOnThePhoneStaysFloating() throws Exception {
        put("2026/7/f.ics", event("f", "DTSTART:20260715T090000\r\nDTEND:20260715T100000\r\n"));
        sync();
        ZoneId here = ZoneId.systemDefault();
        edit("f.ics", v -> {
            v.put(CalendarContract.Events.DTSTART, LocalDateTime.of(2026, 7, 15, 11, 0).atZone(here).toInstant().toEpochMilli());
            v.put(CalendarContract.Events.DTEND, LocalDateTime.of(2026, 7, 15, 12, 0).atZone(here).toInstant().toEpochMilli());
        });
        sync();
        String file = read("f.ics");
        assertTrue(file, file.contains("DTSTART:20260715T110000\r\n"));
        assertTrue(file, file.contains("DTEND:20260715T120000\r\n"));
        assertEquals(0, sync());
    }

    // ---- attendees ----

    @Test
    public void attendeesReachThePhoneAndComeBack() throws Exception {
        put("2026/7/m.ics", event("m", "DTSTART:20260715T090000Z\r\nDTEND:20260715T100000Z\r\n"
                + "ORGANIZER;CN=Alice:mailto:alice@example.com\r\n"
                + "ATTENDEE;CN=Alice;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:alice@example.com\r\n"
                + "ATTENDEE;CN=Bob;RSVP=TRUE;PARTSTAT=NEEDS-ACTION:mailto:bob@example.com\r\n"
                + "ATTENDEE;CUTYPE=ROOM;CN=\"Room 1, East\":mailto:room1@example.com\r\n"));
        sync();
        long eventId = rowId("m.ics");
        assertEquals("alice@example.com", row("m.ics").getAsString(CalendarContract.Events.ORGANIZER));
        assertEquals(List.of(
                "alice@example.com Alice relationship=" + CalendarContract.Attendees.RELATIONSHIP_ORGANIZER
                        + " type=" + CalendarContract.Attendees.TYPE_REQUIRED + " status=" + CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED,
                "bob@example.com Bob relationship=" + CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                        + " type=" + CalendarContract.Attendees.TYPE_REQUIRED + " status=" + CalendarContract.Attendees.ATTENDEE_STATUS_INVITED,
                "room1@example.com Room 1, East relationship=" + CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                        + " type=" + CalendarContract.Attendees.TYPE_RESOURCE + " status=" + CalendarContract.Attendees.ATTENDEE_STATUS_INVITED),
                attendees(eventId));

        // Bob accepts and Carol is invited, as a calendar app writes those.
        ContentValues accepted = new ContentValues();
        accepted.put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED);
        assertEquals(1, context.getContentResolver().update(CalendarContract.Attendees.CONTENT_URI, accepted,
                CalendarContract.Attendees.EVENT_ID + "=? AND " + CalendarContract.Attendees.ATTENDEE_EMAIL + "=?",
                new String[]{Long.toString(eventId), "bob@example.com"}));
        ContentValues carol = new ContentValues();
        carol.put(CalendarContract.Attendees.EVENT_ID, eventId);
        carol.put(CalendarContract.Attendees.ATTENDEE_EMAIL, "carol@example.com");
        carol.put(CalendarContract.Attendees.ATTENDEE_NAME, "Carol");
        carol.put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_OPTIONAL);
        carol.put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE);
        carol.put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED);
        assertNotNull(context.getContentResolver().insert(CalendarContract.Attendees.CONTENT_URI, carol));
        sync();

        String file = read("m.ics");
        assertTrue(file, file.contains("ORGANIZER;CN=Alice:mailto:alice@example.com\r\n"));
        assertTrue("untouched: " + file, file.contains("ATTENDEE;CN=Alice;ROLE=CHAIR;PARTSTAT=ACCEPTED:mailto:alice@example.com\r\n"));
        assertTrue("RSVP kept: " + file, file.contains("ATTENDEE;CN=Bob;RSVP=TRUE;PARTSTAT=ACCEPTED:mailto:bob@example.com\r\n"));
        assertTrue("quoted name kept: " + file, file.contains("ATTENDEE;CUTYPE=ROOM;CN=\"Room 1, East\":mailto:room1@example.com\r\n"));
        assertTrue(file, file.contains("ATTENDEE;CN=Carol;ROLE=OPT-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:carol@example.com\r\n"));
        assertEquals(0, sync());

        assertEquals(4, context.getContentResolver().delete(CalendarContract.Attendees.CONTENT_URI,
                CalendarContract.Attendees.EVENT_ID + "=?", new String[]{Long.toString(eventId)}));
        sync();
        file = read("m.ics");
        assertFalse(file, file.contains("ATTENDEE"));
        assertTrue("the organiser is not the phone's to remove: " + file, file.contains("ORGANIZER;CN=Alice"));
    }

    @Test
    public void attendeesOfAnEventMadeOnThePhone() throws Exception {
        sync();
        long calendarId = onlyCalendar();
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, "Lunch");
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        values.put(CalendarContract.Events.DTSTART, at("2026-07-15T12:00:00Z"));
        values.put(CalendarContract.Events.DTEND, at("2026-07-15T13:00:00Z"));
        long eventId = ContentUris.parseId(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));
        ContentValues dave = new ContentValues();
        dave.put(CalendarContract.Attendees.EVENT_ID, eventId);
        dave.put(CalendarContract.Attendees.ATTENDEE_EMAIL, "dave@example.com");
        dave.put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED);
        dave.put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED);
        assertNotNull(context.getContentResolver().insert(CalendarContract.Attendees.CONTENT_URI, dave));
        sync();
        String file = read(onlyName());
        assertTrue(file, file.contains("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION:mailto:dave@example.com\r\n"));
        assertEquals(0, sync());
    }

    // ---- dates added to a rule ----

    @Test
    public void datesAddedToARuleShowOnThePhone() throws Exception {
        put("recurring/r.ics", utcSeries("r", "RRULE:FREQ=WEEKLY;COUNT=2\r\nRDATE:20260114T090000Z\r\n"));
        put("recurring/only.ics", utcSeries("only", "RDATE:20260107T090000Z,20260109T090000Z\r\n"));
        sync();
        long calendarId = onlyCalendar();
        assertEquals(List.of("2026-01-05T09:00:00Z only", "2026-01-05T09:00:00Z r", "2026-01-07T09:00:00Z only",
                        "2026-01-09T09:00:00Z only", "2026-01-12T09:00:00Z r", "2026-01-14T09:00:00Z r"),
                sorted(instances(calendarId, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")));
    }

    @Test
    public void anAddedDateDeletedOnThePhoneIsExcluded() throws Exception {
        put("recurring/only.ics", utcSeries("only", "RDATE:20260107T090000Z,20260109T090000Z\r\n"));
        sync();
        long calendarId = onlyCalendar();
        cancelLikeAosp(calendarId, "only.ics", at("2026-01-07T09:00:00Z"));
        sync();
        String file = read("only.ics");
        assertTrue(file, file.contains("EXDATE:20260107T090000Z\r\n"));
        assertTrue(file, file.contains("RDATE:20260107T090000Z,20260109T090000Z\r\n"));
        sync();
        assertEquals(List.of("2026-01-05T09:00:00Z only", "2026-01-09T09:00:00Z only"),
                instances(calendarId, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"));
    }

    @Test
    public void aDateAddedOnThePhoneReachesPeergos() throws Exception {
        put("recurring/r.ics", utcSeries("r", "RRULE:FREQ=WEEKLY;COUNT=2\r\n"));
        sync();
        edit("r.ics", v -> {
            v.put(CalendarContract.Events.DTSTART, at("2026-01-05T09:00:00Z"));
            v.put(CalendarContract.Events.DURATION, "PT1H");
            v.put(CalendarContract.Events.RRULE, "FREQ=WEEKLY;COUNT=2");
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
            v.put(CalendarContract.Events.RDATE, "20260114T090000Z");
        });
        sync();
        String file = read("r.ics");
        assertTrue(file, file.contains("RDATE:20260114T090000Z\r\n"));
        assertEquals(0, sync());
    }

    // ---- colour, availability, privacy, status ----

    @Test
    public void coloursComeAndGoBothWays() throws Exception {
        put("2026/7/a.ics", event("a", "DTSTART:20260715T090000Z\r\nDTEND:20260715T100000Z\r\nCOLOR:dodgerblue\r\n"));
        put("2026/7/b.ics", event("b", "DTSTART:20260716T090000Z\r\nDTEND:20260716T100000Z\r\nCOLOR:navy\r\n"));
        sync();
        ContentValues a = row("a.ics");
        assertEquals("dodgerblue", a.getAsString(CalendarContract.Events.EVENT_COLOR_KEY));
        assertEquals(Integer.valueOf(0xff1e90ff), a.getAsInteger(CalendarContract.Events.EVENT_COLOR));
        ContentValues b = row("b.ics");
        assertEquals(null, b.getAsString(CalendarContract.Events.EVENT_COLOR_KEY));
        assertEquals(Integer.valueOf(0xff000080), b.getAsInteger(CalendarContract.Events.EVENT_COLOR));

        edit("a.ics", v -> v.put(CalendarContract.Events.EVENT_COLOR_KEY, "tomato"));
        edit("b.ics", v -> {
            v.putNull(CalendarContract.Events.EVENT_COLOR_KEY);
            v.putNull(CalendarContract.Events.EVENT_COLOR);
        });
        sync();
        assertTrue(read("a.ics"), read("a.ics").contains("COLOR:tomato\r\n"));
        assertFalse(read("b.ics"), read("b.ics").contains("COLOR"));
        assertEquals(0, sync());
    }

    @Test
    public void availabilityPrivacyAndStatusBothWays() throws Exception {
        put("2026/7/p.ics", event("p", "DTSTART:20260715T090000Z\r\nDTEND:20260715T100000Z\r\n"
                + "TRANSP:TRANSPARENT\r\nCLASS:PRIVATE\r\n"));
        put("2026/7/x.ics", event("x", "DTSTART:20260716T090000Z\r\nDTEND:20260716T100000Z\r\nCLASS:X-SECRET\r\n"));
        sync();
        ContentValues p = row("p.ics");
        assertEquals(Integer.valueOf(CalendarContract.Events.AVAILABILITY_FREE), p.getAsInteger(CalendarContract.Events.AVAILABILITY));
        assertEquals(Integer.valueOf(CalendarContract.Events.ACCESS_PRIVATE), p.getAsInteger(CalendarContract.Events.ACCESS_LEVEL));

        edit("p.ics", v -> {
            v.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY);
            v.put(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_PUBLIC);
            v.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_TENTATIVE);
        });
        edit("x.ics", v -> v.put(CalendarContract.Events.TITLE, "Renamed"));
        sync();
        String file = read("p.ics");
        assertTrue(file, file.contains("TRANSP:OPAQUE\r\n"));
        assertTrue(file, file.contains("CLASS:PUBLIC\r\n"));
        assertTrue(file, file.contains("STATUS:TENTATIVE\r\n"));
        assertTrue("a CLASS the phone cannot show stays: " + read("x.ics"), read("x.ics").contains("CLASS:X-SECRET\r\n"));
        assertEquals(0, sync());

        edit("p.ics", v -> v.put(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_DEFAULT));
        sync();
        assertFalse(read("p.ics"), read("p.ics").contains("CLASS"));
    }

    /** Only the location changes, so every other line of a file written elsewhere is still there. */
    @Test
    public void whatThePhoneDidNotChangeIsLeftAsItWas() throws Exception {
        String original = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Elsewhere//EN\r\n"
                + "BEGIN:VTIMEZONE\r\nTZID:" + OUTLOOK_ZONE + "\r\n" + BERLIN_RULES + "END:VTIMEZONE\r\n"
                + "BEGIN:VEVENT\r\nUID:kept\r\nDTSTAMP:20251201T000000Z\r\nSUMMARY:Kept\r\n"
                + "DTSTART;TZID=\"" + OUTLOOK_ZONE + "\":20260105T090000\r\n"
                + "DTEND;TZID=\"" + OUTLOOK_ZONE + "\":20260105T100000\r\n"
                + "RRULE:FREQ=WEEKLY;COUNT=4\r\n"
                + "EXDATE;TZID=\"" + OUTLOOK_ZONE + "\":20260112T090000\r\n"
                + "RDATE;VALUE=PERIOD:20260107T080000Z/PT2H\r\n"
                + "ORGANIZER;CN=Alice:mailto:alice@example.com\r\n"
                + "ATTENDEE;CN=Bob;RSVP=TRUE;X-NUM-GUESTS=0;PARTSTAT=TENTATIVE:mailto:bob@example.com\r\n"
                + "COLOR:navy\r\nCLASS:CONFIDENTIAL\r\nTRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\n"
                + "X-MICROSOFT-CDO-BUSYSTATUS:BUSY\r\nLOCATION:Old\r\n"
                + "BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER;VALUE=DATE-TIME:20260104T090000Z\r\nEND:VALARM\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        put("recurring/kept.ics", original);
        sync();
        edit("kept.ics", v -> v.put(CalendarContract.Events.EVENT_LOCATION, "New"));
        sync();
        String file = read("kept.ics");
        for (String line : original.split("\r\n")) {
            if (line.startsWith("LOCATION") || line.startsWith("DTSTAMP"))
                continue;
            assertTrue("lost " + line + " from " + file, file.contains(line + "\r\n"));
        }
        assertTrue(file, file.contains("LOCATION:New\r\n"));
        assertEquals(0, sync());
    }

    @Test
    public void everyAlarmIsAReminderAndEveryReminderAnAlarm() throws Exception {
        put("2026/7/r.ics", event("r", "DTSTART:20260715T090000Z\r\nDTEND:20260715T100000Z\r\n"
                + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n"
                + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P1D\r\nEND:VALARM\r\n"));
        sync();
        long id = rowId("r.ics");
        assertEquals(List.of(10, 1440), reminders(id));

        // a third added on the phone, and the event touched as a calendar app does
        ContentValues reminder = new ContentValues();
        reminder.put(CalendarContract.Reminders.EVENT_ID, id);
        reminder.put(CalendarContract.Reminders.MINUTES, 60);
        reminder.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
        assertNotNull(context.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, reminder));
        edit("r.ics", v -> v.put(CalendarContract.Events.TITLE, "r touched"));
        sync();
        String file = read("r.ics");
        for (String trigger : List.of("TRIGGER:-PT10M", "TRIGGER:-PT60M", "TRIGGER:-PT1440M"))
            assertTrue("lost " + trigger + " from " + file, file.contains(trigger));
        assertEquals(file, 3, file.split("BEGIN:VALARM", -1).length - 1);
        assertEquals(0, sync());
        assertEquals(List.of(10, 60, 1440), reminders(id));
    }

    // ---- helpers ----

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private static String event(String uid, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Peergos//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20251201T000000Z\r\nSUMMARY:" + uid + "\r\n"
                + extra + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private static String utcSeries(String uid, String extra) {
        return event(uid, "DTSTART:20260105T090000Z\r\nDTEND:20260105T100000Z\r\n" + extra);
    }

    private static String outlookSeries(String rule, String start, String end, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Microsoft Corporation//Outlook 16.0 MIMEDIR//EN\r\n"
                + "BEGIN:VTIMEZONE\r\nTZID:" + OUTLOOK_ZONE + "\r\n" + BERLIN_RULES + "END:VTIMEZONE\r\n"
                + "BEGIN:VEVENT\r\nUID:o\r\nDTSTAMP:20251201T000000Z\r\nSUMMARY:Weekly\r\n"
                + "DTSTART;TZID=\"" + OUTLOOK_ZONE + "\":" + start + "\r\n"
                + "DTEND;TZID=\"" + OUTLOOK_ZONE + "\":" + end + "\r\n"
                + "RRULE:" + rule + "\r\n" + extra + "END:VEVENT\r\nEND:VCALENDAR\r\n";
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

    private String onlyName() throws Exception {
        List<String> names = store().listObjects(directory).stream().map(o -> o.name).collect(Collectors.toList());
        assertEquals(names.toString(), 1, names.size());
        return names.get(0);
    }

    private String read(String name) throws Exception {
        AppDataStore.ObjectRef ref = store().getObject(directory, name).orElseThrow();
        return new String(store().read(ref), StandardCharsets.UTF_8);
    }

    /** Changes the row as a calendar app does, which marks it dirty. */
    private void edit(String name, java.util.function.Consumer<ContentValues> change) throws Exception {
        ContentValues values = new ContentValues();
        change.accept(values);
        assertEquals(1, context.getContentResolver().update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, rowId(name)), values, null, null));
    }

    private void cancelLikeAosp(long calendarId, String series, long instance) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, "whatever");
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        values.put(CalendarContract.Events.ORIGINAL_SYNC_ID, series);
        values.put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instance);
        values.put(CalendarContract.Events.DTSTART, instance);
        values.put(CalendarContract.Events.DTEND, instance + HOUR);
        values.put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED);
        assertNotNull(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));
    }

    private List<Integer> reminders(long eventId) throws Exception {
        List<Integer> out = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI),
                new String[]{CalendarContract.Reminders.MINUTES},
                CalendarContract.Reminders.EVENT_ID + "=?", new String[]{Long.toString(eventId)},
                CalendarContract.Reminders.MINUTES + " ASC")) {
            while (cursor != null && cursor.moveToNext())
                out.add(cursor.getInt(0));
        }
        return out;
    }

    private long rowId(String name) throws Exception {
        return row(name).getAsLong(CalendarContract.Events._ID);
    }

    private ContentValues row(String name) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI), null,
                CalendarContract.Events._SYNC_ID + "=? AND " + CalendarContract.Events.CALENDAR_ID + "=?",
                new String[]{name, Long.toString(onlyCalendar())}, null)) {
            assertTrue("expected " + name + " on the device", cursor != null && cursor.moveToFirst());
            ContentValues values = new ContentValues();
            android.database.DatabaseUtils.cursorRowToContentValues(cursor, values);
            return values;
        }
    }

    private List<String> attendees(long eventId) throws Exception {
        List<String> out = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI),
                new String[]{CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_NAME,
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.ATTENDEE_TYPE,
                        CalendarContract.Attendees.ATTENDEE_STATUS},
                CalendarContract.Attendees.EVENT_ID + "=?", new String[]{Long.toString(eventId)},
                CalendarContract.Attendees.ATTENDEE_EMAIL)) {
            while (cursor != null && cursor.moveToNext())
                out.add(cursor.getString(0) + " " + cursor.getString(1) + " relationship=" + cursor.getInt(2)
                        + " type=" + cursor.getInt(3) + " status=" + cursor.getInt(4));
        }
        return out;
    }

    private static List<String> sorted(List<String> items) {
        List<String> copy = new ArrayList<>(items);
        java.util.Collections.sort(copy);
        return copy;
    }

    /** What a calendar app would draw between two instants. */
    private List<String> instances(long calendarId, String from, String to) {
        Uri.Builder range = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(range, at(from));
        ContentUris.appendId(range, at(to));
        List<String> out = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(range.build(),
                new String[]{CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE},
                CalendarContract.Instances.CALENDAR_ID + "=? AND (" + CalendarContract.Instances.STATUS + " IS NULL OR "
                        + CalendarContract.Instances.STATUS + "!=" + CalendarContract.Events.STATUS_CANCELED + ")",
                new String[]{Long.toString(calendarId)},
                CalendarContract.Instances.BEGIN)) {
            while (cursor != null && cursor.moveToNext())
                out.add(Instant.ofEpochMilli(cursor.getLong(0)) + " " + cursor.getString(1));
        }
        return out;
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
