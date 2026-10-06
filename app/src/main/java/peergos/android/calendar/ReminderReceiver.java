package peergos.android.calendar;

import android.Manifest;
import android.accounts.Account;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.provider.CalendarContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import peergos.android.R;
import peergos.android.sync.PeergosAccount;

/**
 * Rings the reminders of the Peergos calendars, as a calendar app does for its own events.
 *
 * The mirror writes each event's reminder into the phone's calendar, whose provider keeps the
 * alarm and broadcasts this when one is due, waking the app if need be. Left to the phone's
 * calendar app, a reminder rings only if there is one and it shows these calendars, and on a
 * phone without one it never rings at all. A user whose calendar app rings them as well turns
 * one of the two off in the system notification settings.
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "PeergosCalendar";
    static final String CHANNEL_ID = "calendar-reminders";
    /** Which reminders this app has rung, as "event@start@alarm time". The provider's own
     *  record, the alert's state, belongs to the calendar apps: one of them that finds an alert
     *  already marked fired posts it without a sound, so writing it would silence theirs. Nor is
     *  the alert's row id kept, as calendar apps do not keep it either: the provider can delete a
     *  row and hand its id to the next one, which may be another event's reminder. */
    static final String RUNG = "peergos-reminders";
    private static final String RUNG_KEY = "rung";
    /** How late a reminder still rings: the provider schedules an alarm up to two hours after
     *  it was due, for an event that arrived late, and fires missed ones once the phone is on. */
    private static final long LATE_MS = 2 * DateUtils.HOUR_IN_MILLIS;
    /** Before this app has rung anything, the alerts already past were the phone's calendar
     *  app's to ring, so only one that has just come due rings rather than a burst of them. */
    private static final long JUST_DUE_MS = 5 * DateUtils.MINUTE_IN_MILLIS;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (! CalendarContract.ACTION_EVENT_REMINDER.equals(intent.getAction()))
            return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                ringDue(context.getApplicationContext());
            } catch (Exception e) {
                Log.w(TAG, "Could not ring the calendar reminders", e);
            } finally {
                pending.finish();
            }
        }, "PeergosReminders").start();
    }

    /** Posts every reminder of ours that is due and this app has not rung yet. Synchronized, as
     *  two broadcasts close together would otherwise both find the same one not yet rung. */
    static synchronized void ringDue(Context context) {
        if (! granted(context, Manifest.permission.READ_CALENDAR))
            return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ! granted(context, Manifest.permission.POST_NOTIFICATIONS))
            return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        createChannel(manager);
        // the user rings them with some other app, and nothing here is wanted
        if (! manager.areNotificationsEnabled()
                || manager.getNotificationChannel(CHANNEL_ID).getImportance() == NotificationManager.IMPORTANCE_NONE)
            return;
        // Turning the calendar sync off leaves the mirrored calendars on the phone, no longer
        // kept up to date, and their reminders are not this app's to ring any more.
        Optional<Account> account = PeergosAccount.existing(context)
                .filter(a -> ContentResolver.getSyncAutomatically(a, CalendarContract.AUTHORITY));
        if (account.isEmpty())
            return;
        ContentResolver resolver = context.getContentResolver();
        List<Long> calendars = ourCalendars(resolver, account.get());
        if (calendars.isEmpty())
            return;
        long now = System.currentTimeMillis();
        // fired as well as scheduled: a calendar app that got there first has marked it fired
        String selection = "(" + CalendarContract.CalendarAlerts.STATE + "=? OR "
                + CalendarContract.CalendarAlerts.STATE + "=?) AND "
                + CalendarContract.CalendarAlerts.ALARM_TIME + "<=? AND "
                + CalendarContract.CalendarAlerts.ALARM_TIME + ">? AND "
                + CalendarContract.CalendarAlerts.CALENDAR_ID + " IN (" + TextUtils.join(",", calendars) + ")";
        String[] args = {Integer.toString(CalendarContract.CalendarAlerts.STATE_SCHEDULED),
                Integer.toString(CalendarContract.CalendarAlerts.STATE_FIRED),
                Long.toString(now), Long.toString(now - LATE_MS)};
        String[] projection = {CalendarContract.CalendarAlerts.EVENT_ID, CalendarContract.CalendarAlerts.ALARM_TIME,
                CalendarContract.CalendarAlerts.TITLE, CalendarContract.CalendarAlerts.BEGIN,
                CalendarContract.CalendarAlerts.END, CalendarContract.CalendarAlerts.ALL_DAY,
                CalendarContract.CalendarAlerts.EVENT_LOCATION};
        SharedPreferences prefs = context.getSharedPreferences(RUNG, Context.MODE_PRIVATE);
        boolean firstRun = ! prefs.contains(RUNG_KEY);
        Set<String> rung = new HashSet<>(prefs.getStringSet(RUNG_KEY, Collections.emptySet()));
        // past the point a reminder still rings, it cannot come round again
        rung.removeIf(key -> Long.parseLong(key.substring(key.lastIndexOf('@') + 1)) <= now - LATE_MS);
        try (Cursor cursor = resolver.query(CalendarContract.CalendarAlerts.CONTENT_URI, projection,
                selection, args, CalendarContract.CalendarAlerts.BEGIN)) {
            while (cursor != null && cursor.moveToNext()) {
                long event = cursor.getLong(0);
                long alarmTime = cursor.getLong(1);
                long begin = cursor.getLong(3);
                if (! rung.add(event + "@" + begin + "@" + alarmTime))
                    continue;
                if (firstRun && alarmTime < now - JUST_DUE_MS)
                    continue;
                // one notification per occurrence: a second reminder for it replaces the first
                manager.notify(CHANNEL_ID, Objects.hash(event, begin), notification(context,
                        cursor.getString(2), begin, cursor.getLong(4), cursor.getInt(5) != 0, cursor.getString(6)));
            }
        }
        prefs.edit().putStringSet(RUNG_KEY, rung).apply();
    }

    private static List<Long> ourCalendars(ContentResolver resolver, Account account) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{CalendarContract.Calendars._ID},
                CalendarContract.Calendars.ACCOUNT_TYPE + "=? AND " + CalendarContract.Calendars.ACCOUNT_NAME + "=?",
                new String[]{account.type, account.name}, null)) {
            while (cursor != null && cursor.moveToNext())
                ids.add(cursor.getLong(0));
        }
        return ids;
    }

    /** High importance, as calendar apps ring their reminders: a sound and a heads-up. */
    private static void createChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Calendar reminders",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("When an event in a Peergos calendar is about to start");
        manager.createNotificationChannel(channel);
    }

    private static Notification notification(Context context, String title, long begin, long end,
                                             boolean allDay, String location) {
        // an all-day event is stored at midnight UTC, and shown as its date, not a time
        String when = allDay
                ? DateUtils.formatDateRange(context, new Formatter(), begin, begin,
                        DateUtils.FORMAT_SHOW_DATE, "UTC").toString()
                : DateUtils.formatDateRange(context, begin, end, DateUtils.FORMAT_SHOW_TIME);
        String text = TextUtils.isEmpty(location) ? when : when + " · " + location;
        Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(TextUtils.isEmpty(title) ? "(No title)" : title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        if (open != null)
            builder.setContentIntent(PendingIntent.getActivity(context, 0, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return builder.build();
    }

    private static boolean granted(Context context, String permission) {
        return ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }
}
