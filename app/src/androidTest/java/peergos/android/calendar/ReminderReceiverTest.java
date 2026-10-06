package peergos.android.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import peergos.android.sync.PeergosAccount;

/**
 * The app rings the reminders of its own calendars, and only those, once each. It keeps its own
 * record of what it has rung and leaves the provider's alert state alone, which belongs to the
 * calendar apps: one that finds an alert already marked fired shows it without a sound.
 */
@RunWith(AndroidJUnit4.class)
public class ReminderReceiverTest {

    private static final String USER = "androidtest-reminder-user";
    private static final String OURS = "Ours to ring";
    private static final String THEIRS = "Someone else's";

    @Rule
    public GrantPermissionRule permissions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? GrantPermissionRule.grant(android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR, android.Manifest.permission.POST_NOTIFICATIONS)
            : GrantPermissionRule.grant(android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR);

    private final List<Uri> calendars = new ArrayList<>();

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static Uri asSyncAdapter(Uri uri, String name, String type) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, type)
                .build();
    }

    /** Each test starts as a fresh install, before the app has rung anything. */
    @Before
    public void forgetWhatWasRung() {
        context().getSharedPreferences(ReminderReceiver.RUNG, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void cleanUp() {
        context().getSystemService(NotificationManager.class).cancelAll();
        ContentResolver resolver = context().getContentResolver();
        for (Uri calendar : calendars)
            resolver.delete(calendar, null, null);
        AccountManager manager = AccountManager.get(context());
        for (Account account : manager.getAccountsByType(PeergosAccount.TYPE))
            manager.removeAccountExplicitly(account);
    }

    @Test
    public void ringsOnlyOurOwnDueReminders() {
        Account account = syncing(PeergosAccount.ensure(context(), USER));
        long now = System.currentTimeMillis();
        long ours = dueAlert(calendar(account.name, account.type), OURS, now);
        long theirs = dueAlert(calendar("someone", CalendarContract.ACCOUNT_TYPE_LOCAL), THEIRS, now);

        ReminderReceiver.ringDue(context());

        assertEquals("our reminder should be posted once", 1, posted(OURS));
        assertEquals("another account's reminder is not ours to ring", 0, postedAfterAWhile(THEIRS));
        assertEquals("the alert's state is the calendar apps' record, not ours",
                CalendarContract.CalendarAlerts.STATE_SCHEDULED, state(ours));
        assertEquals(CalendarContract.CalendarAlerts.STATE_SCHEDULED, state(theirs));

        // the provider can broadcast again before the user has looked, and a rung reminder
        // must not come back as a second notification
        context().getSystemService(NotificationManager.class).cancelAll();
        ReminderReceiver.ringDue(context());
        assertEquals("a rung reminder rings once", 0, postedAfterAWhile(OURS));
    }

    @Test
    public void oneAnotherAppHasAlreadyRungStillRingsHere() {
        Account account = syncing(PeergosAccount.ensure(context(), USER));
        long alert = dueAlert(calendar(account.name, account.type), OURS, System.currentTimeMillis());
        ContentValues fired = new ContentValues();
        fired.put(CalendarContract.CalendarAlerts.STATE, CalendarContract.CalendarAlerts.STATE_FIRED);
        context().getContentResolver().update(
                ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, alert), fired, null, null);

        ReminderReceiver.ringDue(context());

        assertEquals(1, posted(OURS));
    }

    @Test
    public void aFreshInstallDoesNotRingWhatWasAlreadyPast() {
        Account account = syncing(PeergosAccount.ensure(context(), USER));
        long calendar = calendar(account.name, account.type);
        long now = System.currentTimeMillis();
        dueAlert(calendar, THEIRS, now - 30 * 60_000);

        ReminderReceiver.ringDue(context());
        assertEquals("a reminder from before the app rang any is not rung late", 0, postedAfterAWhile(THEIRS));

        dueAlert(calendar, OURS, System.currentTimeMillis());
        ReminderReceiver.ringDue(context());
        assertEquals("one that comes due afterwards rings", 1, posted(OURS));
        assertEquals(0, showing(THEIRS));
    }

    @Test
    public void anAlertTheProviderWritesAgainDoesNotRingAgain() {
        Account account = syncing(PeergosAccount.ensure(context(), USER));
        long now = System.currentTimeMillis();
        long first = dueAlert(calendar(account.name, account.type), OURS, now);
        ReminderReceiver.ringDue(context());
        assertEquals(1, posted(OURS));

        // the provider rewrites an event's alerts as it reschedules, under new row ids
        long event = eventOf(first);
        context().getContentResolver().delete(
                ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, first), null, null);
        alertFor(event, now, null);
        context().getSystemService(NotificationManager.class).cancelAll();
        ReminderReceiver.ringDue(context());

        assertEquals("the same reminder of the same event rings once", 0, postedAfterAWhile(OURS));
    }

    @Test
    public void anotherEventsReminderUnderAReusedRowIdStillRings() {
        Account account = syncing(PeergosAccount.ensure(context(), USER));
        long calendar = calendar(account.name, account.type);
        long now = System.currentTimeMillis();
        long rowId = dueAlert(calendar, THEIRS, now);
        ReminderReceiver.ringDue(context());
        assertEquals(1, posted(THEIRS));

        // that event goes, and its alert row's id comes back for another event's reminder
        context().getContentResolver().delete(
                ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, rowId), null, null);
        long other = dueAlert(calendar, OURS, now);
        context().getContentResolver().delete(
                ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, other), null, null);
        alertFor(eventTitled(OURS), now, rowId);
        ReminderReceiver.ringDue(context());

        assertEquals("a new event's reminder rings, whatever row id it was given", 1, posted(OURS));
    }

    @Test
    public void withTheCalendarSyncOffNothingRings() {
        // the mirrored calendars stay on the phone when the sync is turned off, no longer kept
        // up to date, and a reminder from them could be for an event that has since moved
        Account account = PeergosAccount.ensure(context(), USER);
        dueAlert(calendar(account.name, account.type), OURS, System.currentTimeMillis());

        ReminderReceiver.ringDue(context());

        assertEquals(0, postedAfterAWhile(OURS));
    }

    @Test
    public void aReminderThatIsNotDueYetWaits() {
        Account account = syncing(PeergosAccount.ensure(context(), USER));
        long now = System.currentTimeMillis();
        long later = dueAlert(calendar(account.name, account.type), OURS, now + 10 * 60_000);

        ReminderReceiver.ringDue(context());

        assertEquals(0, postedAfterAWhile(OURS));
        assertEquals(CalendarContract.CalendarAlerts.STATE_SCHEDULED, state(later));
    }

    /** The account with its calendar sync on, as the app leaves it once the user turns it on. */
    private static Account syncing(Account account) {
        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1);
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true);
        return account;
    }

    private long calendar(String name, String type) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, name);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, type);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, name);
        values.put(CalendarContract.Calendars.NAME, "reminders");
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Reminders");
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        Uri inserted = context().getContentResolver().insert(
                asSyncAdapter(CalendarContract.Calendars.CONTENT_URI, name, type), values);
        long id = ContentUris.parseId(inserted);
        calendars.add(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id), name, type));
        return id;
    }

    /** An event starting at `alarmTime` with an alert for then, as the provider schedules one
     *  for a reminder at the time of the event. */
    private long dueAlert(long calendarId, String title, long alarmTime) {
        ContentResolver resolver = context().getContentResolver();
        ContentValues event = new ContentValues();
        event.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        event.put(CalendarContract.Events.TITLE, title);
        event.put(CalendarContract.Events.DTSTART, alarmTime);
        event.put(CalendarContract.Events.DTEND, alarmTime + 60 * 60_000);
        event.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        long eventId = ContentUris.parseId(resolver.insert(CalendarContract.Events.CONTENT_URI, event));
        return alertFor(eventId, alarmTime, null);
    }

    /** The provider's alert row for an event starting at `alarmTime`, under `rowId` if given, as
     *  the provider writes one for a reminder at the time of the event. */
    private long alertFor(long eventId, long alarmTime, Long rowId) {
        ContentResolver resolver = context().getContentResolver();
        ContentValues alert = new ContentValues();
        if (rowId != null)
            alert.put(CalendarContract.CalendarAlerts._ID, rowId);
        alert.put(CalendarContract.CalendarAlerts.EVENT_ID, eventId);
        alert.put(CalendarContract.CalendarAlerts.BEGIN, alarmTime);
        alert.put(CalendarContract.CalendarAlerts.END, alarmTime + 60 * 60_000);
        alert.put(CalendarContract.CalendarAlerts.ALARM_TIME, alarmTime);
        alert.put(CalendarContract.CalendarAlerts.CREATION_TIME, System.currentTimeMillis());
        alert.put(CalendarContract.CalendarAlerts.RECEIVED_TIME, 0);
        alert.put(CalendarContract.CalendarAlerts.NOTIFY_TIME, 0);
        alert.put(CalendarContract.CalendarAlerts.STATE, CalendarContract.CalendarAlerts.STATE_SCHEDULED);
        alert.put(CalendarContract.CalendarAlerts.MINUTES, 0);
        return ContentUris.parseId(resolver.insert(CalendarContract.CalendarAlerts.CONTENT_URI, alert));
    }

    private long eventOf(long alertId) {
        try (Cursor cursor = context().getContentResolver().query(
                ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, alertId),
                new String[]{CalendarContract.CalendarAlerts.EVENT_ID}, null, null, null)) {
            assertTrue("the alert should be there", cursor != null && cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    /** The newest event titled `title`. */
    private long eventTitled(String title) {
        try (Cursor cursor = context().getContentResolver().query(CalendarContract.Events.CONTENT_URI,
                new String[]{CalendarContract.Events._ID}, CalendarContract.Events.TITLE + "=?",
                new String[]{title}, CalendarContract.Events._ID + " DESC")) {
            assertTrue("the event should be there", cursor != null && cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private int state(long alertId) {
        try (Cursor cursor = context().getContentResolver().query(
                ContentUris.withAppendedId(CalendarContract.CalendarAlerts.CONTENT_URI, alertId),
                new String[]{CalendarContract.CalendarAlerts.STATE}, null, null, null)) {
            assertTrue("the alert should still be there", cursor != null && cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    /** What is showing for `title` once the posts already made have landed: notifications are
     *  posted asynchronously, so one just asked for is not in the active list at once. */
    private int posted(String title) {
        long until = SystemClock.uptimeMillis() + 5_000;
        int count = showing(title);
        while (count == 0 && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100);
            count = showing(title);
        }
        return count;
    }

    /** The same, for a post that should not happen: given time to land, and then counted. */
    private int postedAfterAWhile(String title) {
        SystemClock.sleep(1_500);
        return showing(title);
    }

    private int showing(String title) {
        int count = 0;
        for (StatusBarNotification posted : context().getSystemService(NotificationManager.class).getActiveNotifications()) {
            CharSequence shown = posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            if (ReminderReceiver.CHANNEL_ID.equals(posted.getTag()) && shown != null && title.contentEquals(shown)) {
                assertEquals(ReminderReceiver.CHANNEL_ID, posted.getNotification().getChannelId());
                count++;
            }
        }
        return count;
    }
}
