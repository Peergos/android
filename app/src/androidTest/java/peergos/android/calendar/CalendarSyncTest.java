package peergos.android.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.provider.CalendarContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Optional;

import peergos.android.sync.PeergosAccount;

@RunWith(AndroidJUnit4.class)
public class CalendarSyncTest {

    private static final String USER = "androidtest-calendar-user";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void removeAccount() {
        AccountManager manager = AccountManager.get(context());
        for (Account account : manager.getAccountsByType(PeergosAccount.TYPE))
            manager.removeAccountExplicitly(account);
    }

    @Test
    public void registersAnAccountThatSyncsCalendars() {
        Account account = PeergosAccount.ensure(context(), USER);
        assertEquals(USER, account.name);
        assertEquals(PeergosAccount.TYPE, account.type);

        assertTrue("the account should be discoverable",
                PeergosAccount.existing(context()).isPresent());
        PeergosAccount.startSyncing(account, CalendarContract.AUTHORITY);
        assertEquals(1, ContentResolver.getIsSyncable(account, CalendarContract.AUTHORITY));
        assertTrue("automatic sync should be on",
                ContentResolver.getSyncAutomatically(account, CalendarContract.AUTHORITY));
        // The sync manager records this asynchronously, so poll rather than read once.
        boolean scheduled = false;
        for (int i = 0; i < 40 && ! scheduled; i++) {
            scheduled = ! ContentResolver.getPeriodicSyncs(account, CalendarContract.AUTHORITY).isEmpty();
            if (! scheduled)
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
        }
        assertTrue("a periodic sync should be scheduled", scheduled);
    }

    @Test
    public void ensureIsIdempotentAndReplacesAnotherUser() {
        PeergosAccount.ensure(context(), USER);
        PeergosAccount.ensure(context(), USER);
        assertEquals(1, AccountManager.get(context()).getAccountsByType(PeergosAccount.TYPE).length);

        // A different user signing in must not leave the previous user's calendars behind.
        PeergosAccount.ensure(context(), "someone-else");
        Account[] accounts = AccountManager.get(context()).getAccountsByType(PeergosAccount.TYPE);
        assertEquals(1, accounts.length);
        assertEquals("someone-else", accounts[0].name);
    }

    @Test
    public void mapsATimedEvent() {
        ContentValues values = translate("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
                + "UID:timed\r\nSUMMARY:Stand up\r\nLOCATION:Room 1\r\n"
                + "DTSTART:20240315T090000Z\r\nDTEND:20240315T100000Z\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n");
        assertEquals("Stand up", values.getAsString(CalendarContract.Events.TITLE));
        assertEquals("Room 1", values.getAsString(CalendarContract.Events.EVENT_LOCATION));
        assertEquals(Integer.valueOf(0), values.getAsInteger(CalendarContract.Events.ALL_DAY));
        assertEquals(Long.valueOf(1710493200000L), values.getAsLong(CalendarContract.Events.DTSTART));
        assertEquals(Long.valueOf(1710496800000L), values.getAsLong(CalendarContract.Events.DTEND));
        assertTrue(values.get(CalendarContract.Events.RRULE) == null);
    }

    @Test
    public void mapsAnAllDayEventToUtcMidnight() {
        ContentValues values = translate("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
                + "UID:allday\r\nSUMMARY:Holiday\r\nDTSTART;VALUE=DATE:20240315\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n");
        assertEquals(Integer.valueOf(1), values.getAsInteger(CalendarContract.Events.ALL_DAY));
        assertEquals("UTC", values.getAsString(CalendarContract.Events.EVENT_TIMEZONE));
        assertEquals(Long.valueOf(1710460800000L), values.getAsLong(CalendarContract.Events.DTSTART));
    }

    /** The contract rejects a recurring event that carries an end instead of a duration. */
    @Test
    public void mapsARecurringEventToADuration() {
        ContentValues values = translate("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
                + "UID:weekly\r\nSUMMARY:Weekly sync\r\n"
                + "DTSTART:20240315T090000Z\r\nDTEND:20240315T100000Z\r\n"
                + "RRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n");
        assertEquals("FREQ=WEEKLY", values.getAsString(CalendarContract.Events.RRULE));
        assertEquals("PT60M", values.getAsString(CalendarContract.Events.DURATION));
        assertTrue("a recurring event must not also carry DTEND",
                values.get(CalendarContract.Events.DTEND) == null);
    }

    /**
     * A calendar collection also carries tasks, which CalendarContract has no table for.
     * The one with a DTSTART is the dangerous case: it would map cleanly onto an event row
     * and show up on the phone's calendar as an event.
     */
    @Test
    public void skipsTasks() {
        assertTrue(EventTranslator.toEvent("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n"
                + "UID:milk\r\nSUMMARY:Buy milk\r\nDTSTART:20240315T090000Z\r\nDUE:20240315T170000Z\r\n"
                + "END:VTODO\r\nEND:VCALENDAR\r\n", 1).isEmpty());
        assertTrue(EventTranslator.toEvent("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n"
                + "UID:someday\r\nSUMMARY:Someday\r\nEND:VTODO\r\nEND:VCALENDAR\r\n", 1).isEmpty());
    }

    @Test
    public void skipsAnObjectWithNoStart() {
        assertTrue(EventTranslator.toEvent("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
                + "UID:nostart\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n", 1).isEmpty());
        assertTrue(EventTranslator.toEvent("not a calendar at all", 1).isEmpty());
    }

    private static ContentValues translate(String ics) {
        Optional<ContentValues> values = EventTranslator.toEvent(ics, 42);
        assertTrue("should have produced a row", values.isPresent());
        assertEquals(Long.valueOf(42), values.get().getAsLong(CalendarContract.Events.CALENDAR_ID));
        return values.get();
    }
}
