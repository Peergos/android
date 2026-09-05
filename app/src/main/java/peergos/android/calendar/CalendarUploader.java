package peergos.android.calendar;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.CalendarStore;
import peergos.server.webdav.caldav.ICal;

/**
 * Pushes local calendar edits back into Peergos.
 *
 * Peergos is the source of truth. Where both sides changed an event since the last sync —
 * which the stored ETag makes exact — the Peergos version is kept and the local edit is
 * written out as a *new* event rather than discarded. The user then sees both and decides,
 * which is the one outcome that cannot silently lose work.
 *
 * Exceptions to a recurring event take the same route. Android models one as its own row
 * pointing at the series through ORIGINAL_SYNC_ID, while the web app models it as a
 * separate file named after the parent and the recurrence id; until that mapping is
 * written, an edited instance is preserved as a standalone event and the series is
 * restored, so the edit survives even though it does not become a true exception.
 */
public class CalendarUploader {

    private static final String TAG = "PeergosCalendar";

    private static final String[] EVENT_COLUMNS = {
            CalendarContract.Events._ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.SYNC_DATA1,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.ORIGINAL_SYNC_ID,
    };

    private final ContentProviderClient provider;
    private final Account account;
    private final CalendarStore store;

    public CalendarUploader(ContentProviderClient provider, Account account, CalendarStore store) {
        this.provider = provider;
        this.account = account;
        this.store = store;
    }

    /** @return how many local changes were pushed. */
    public int upload(long calendarId, String directory) throws Exception {
        int pushed = 0;
        List<Row> rows = dirtyRows(calendarId);
        for (Row row : rows) {
            try {
                pushed += apply(row, directory) ? 1 : 0;
            } catch (Exception e) {
                // One bad event should not stop the rest; it stays dirty and is retried.
                Log.w(TAG, "Could not upload event " + row.id, e);
            }
        }
        return pushed;
    }

    private boolean apply(Row row, String directory) throws Exception {
        if (row.deleted)
            return delete(row, directory);
        if (row.originalSyncId != null) {
            // An edited instance of a series: keep it as its own event and let the
            // download pass put the untouched series back.
            duplicate(row, directory);
            purge(row.id);
            return true;
        }
        if (row.syncId == null)
            return create(row, directory);
        return update(row, directory);
    }

    private boolean delete(Row row, String directory) throws Exception {
        if (row.syncId == null) {
            purge(row.id);
            return false;
        }
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, row.syncId);
        if (remote.isPresent() && ! remote.get().etag().equals(row.etag)) {
            // Changed in Peergos since we last saw it, so the delete loses: dropping the
            // row lets the download pass restore the newer version.
            Log.i(TAG, "Deletion of " + row.syncId + " conflicts with a remote change; keeping the remote copy");
            purge(row.id);
            return false;
        }
        remote.ifPresent(object -> store.deleteObject(directory, object));
        purge(row.id);
        return true;
    }

    private boolean create(Row row, String directory) throws Exception {
        String uid = UUID.randomUUID().toString();
        write(directory, uid + CalendarStore.ICS_SUFFIX, ICalWriter.create(uid, properties(row)), row.id);
        return true;
    }

    private boolean update(Row row, String directory) throws Exception {
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, row.syncId);
        if (remote.isEmpty()) {
            // Removed in Peergos while we were editing. Writing it back under the same name
            // resurrects the user's version rather than dropping their edit.
            write(directory, row.syncId, ICalWriter.create(uidFor(row), properties(row)), row.id);
            return true;
        }
        String existing = new String(store.read(remote.get()), StandardCharsets.UTF_8);
        if (! remote.get().etag().equals(row.etag)) {
            duplicate(row, directory);
            purge(row.id);
            return true;
        }
        write(directory, row.syncId, ICalWriter.patch(existing, properties(row), Collections.emptyList()), row.id);
        return true;
    }

    /** Writes the local version as a new event, leaving the remote one alone. */
    private void duplicate(Row row, String directory) throws Exception {
        String uid = UUID.randomUUID().toString();
        List<ICalWriter.Line> properties = properties(row);
        properties.add(ICalWriter.text("SUMMARY", row.title + " (edited on this device)"));
        store.putObject(directory, uid + CalendarStore.ICS_SUFFIX,
                ICalWriter.create(uid, properties).getBytes(StandardCharsets.UTF_8), Optional.empty());
        Log.i(TAG, "Kept a conflicting local edit as " + uid);
    }

    private void write(String directory, String name, String ics, long rowId) throws Exception {
        // The ref the write hands back is where the new ETag comes from: reading it through
        // a listing instead would walk the whole shard again, having just invalidated it.
        AppDataStore.ObjectRef stored = store.putObject(directory, name,
                ics.getBytes(StandardCharsets.UTF_8), store.getObject(directory, name));
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events._SYNC_ID, name);
        values.put(CalendarContract.Events.SYNC_DATA1, stored.etag());
        values.put(CalendarContract.Events.DIRTY, 0);
        provider.update(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values,
                CalendarContract.Events._ID + "=?", new String[]{Long.toString(rowId)});
    }

    /** The UID already in the file if we can read it, so an update keeps the event's identity. */
    private String uidFor(Row row) {
        return row.syncId != null && row.syncId.endsWith(CalendarStore.ICS_SUFFIX)
                ? row.syncId.substring(0, row.syncId.length() - CalendarStore.ICS_SUFFIX.length())
                : UUID.randomUUID().toString();
    }

    /**
     * The properties the platform calendar can express. DTEND is always written, even for
     * a recurring event where the contract holds a duration instead, because the web
     * calendar app treats an event without one as incomplete.
     */
    private List<ICalWriter.Line> properties(Row row) {
        List<ICalWriter.Line> lines = new ArrayList<>();
        lines.add(ICalWriter.text("SUMMARY", row.title == null ? "" : row.title));
        if (row.description != null)
            lines.add(ICalWriter.text("DESCRIPTION", row.description));
        if (row.location != null)
            lines.add(ICalWriter.text("LOCATION", row.location));
        long end = endOf(row);
        if (row.allDay) {
            lines.add(ICalWriter.date("DTSTART", row.start));
            lines.add(ICalWriter.date("DTEND", Math.max(end, row.start + Duration.ofDays(1).toMillis())));
        } else {
            lines.add(ICalWriter.timestamp("DTSTART", row.start));
            lines.add(ICalWriter.timestamp("DTEND", end));
        }
        if (row.rrule != null)
            lines.add(ICalWriter.raw("RRULE", row.rrule));
        lines.add(ICalWriter.timestamp("LAST-MODIFIED", System.currentTimeMillis()));
        lines.add(ICalWriter.timestamp("DTSTAMP", System.currentTimeMillis()));
        return lines;
    }

    private static long endOf(Row row) {
        if (row.end != null)
            return row.end;
        if (row.duration != null) {
            Optional<Duration> length = ICal.parseDuration(row.duration);
            if (length.isPresent())
                return row.start + length.get().toMillis();
        }
        return row.start + (row.allDay ? Duration.ofDays(1).toMillis() : Duration.ofHours(1).toMillis());
    }

    private void purge(long rowId) throws Exception {
        provider.delete(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                CalendarContract.Events._ID + "=?", new String[]{Long.toString(rowId)});
    }

    private List<Row> dirtyRows(long calendarId) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                EVENT_COLUMNS,
                CalendarContract.Events.CALENDAR_ID + "=? AND ("
                        + CalendarContract.Events.DIRTY + "=1 OR " + CalendarContract.Events.DELETED + "=1)",
                new String[]{Long.toString(calendarId)}, null)) {
            while (cursor != null && cursor.moveToNext())
                rows.add(Row.from(cursor));
        }
        return rows;
    }

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();
    }

    private static final class Row {
        long id;
        String syncId;
        String etag;
        boolean deleted;
        String title;
        String description;
        String location;
        long start;
        Long end;
        String duration;
        boolean allDay;
        String rrule;
        String originalSyncId;

        static Row from(Cursor c) {
            Row row = new Row();
            row.id = c.getLong(0);
            row.syncId = c.isNull(1) ? null : c.getString(1);
            row.etag = c.isNull(2) ? "" : c.getString(2);
            row.deleted = c.getInt(3) == 1;
            row.title = c.isNull(4) ? null : c.getString(4);
            row.description = c.isNull(5) ? null : c.getString(5);
            row.location = c.isNull(6) ? null : c.getString(6);
            row.start = c.isNull(7) ? 0 : c.getLong(7);
            row.end = c.isNull(8) ? null : c.getLong(8);
            row.duration = c.isNull(9) ? null : c.getString(9);
            row.allDay = c.getInt(10) == 1;
            row.rrule = c.isNull(11) ? null : c.getString(11);
            row.originalSyncId = c.isNull(12) ? null : c.getString(12);
            return row;
        }
    }
}
