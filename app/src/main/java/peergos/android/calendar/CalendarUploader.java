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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * One occurrence of a series changed or deleted on the phone arrives as an exception row,
 * pointing at the series through ORIGINAL_SYNC_ID. Peergos has no such thing: the web app
 * excludes the occurrence from the series with an EXDATE and, for a change, keeps the new
 * version as an event of its own. An exception is written the same way, so the web app,
 * the CalDAV bridge and this mirror all read it back as one model.
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
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.SYNC_DATA2,
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
        List<Row> rows = separateSplits(calendarId, dirtyRows(calendarId));
        // Exceptions last: a series uploaded in this same pass gets the name an exception
        // needs, and an exception's EXDATE must not land under a series edit that would then
        // see the file changed and take it for a conflict.
        rows.sort(Comparator.comparing(Row::isException));
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

    /**
     * "This and following" changed on the phone. The provider splits the series by copying
     * its row — _SYNC_ID and all — into a new series from that occurrence on, and cuts the
     * original short, without marking it dirty. Left alone, the copy would be
     * uploaded over the original's file, losing the occurrences before the split, and the
     * cut would never reach Peergos. So the copy is made a new event, and the original,
     * the earliest row of that name, is uploaded as it now ends.
     */
    private List<Row> separateSplits(long calendarId, List<Row> dirty) throws Exception {
        List<Row> out = new ArrayList<>(dirty);
        for (Row row : dirty) {
            if (row.isException() || row.deleted || row.syncId == null)
                continue;
            List<Row> named = rowsNamed(calendarId, row.syncId);
            if (named.size() < 2)
                continue;
            Row original = named.get(0);
            for (Row copy : named.subList(1, named.size())) {
                for (Row each : out) {
                    if (each.id == copy.id) {
                        each.syncId = null;
                        each.etag = "";
                    }
                }
            }
            if (out.stream().noneMatch(r -> r.id == original.id))
                out.add(original);
        }
        return out;
    }

    /** The series and single events filed under this name, oldest row first. */
    private List<Row> rowsNamed(long calendarId, String syncId) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                EVENT_COLUMNS,
                CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=? AND "
                        + CalendarContract.Events.ORIGINAL_SYNC_ID + " IS NULL AND "
                        + CalendarContract.Events.ORIGINAL_ID + " IS NULL",
                new String[]{Long.toString(calendarId), syncId}, CalendarContract.Events._ID)) {
            while (cursor != null && cursor.moveToNext())
                rows.add(Row.from(cursor));
        }
        return rows;
    }

    private boolean apply(Row row, String directory) throws Exception {
        if (row.isException())
            return exception(row, directory);
        if (row.deleted)
            return delete(row, directory);
        if (row.syncId == null)
            return create(row, directory);
        return update(row, directory);
    }

    /**
     * One occurrence changed or deleted. Its exclusion goes into whatever version of the
     * series Peergos now holds rather than being checked against the one the phone last
     * saw: it touches only that occurrence, so there is nothing in a newer version for it
     * to overwrite. The row goes either way, and the download pass brings back the series
     * without the occurrence and, for a change, the new event in its place.
     */
    private boolean exception(Row row, String directory) throws Exception {
        Optional<String> series = row.originalSyncId != null
                ? Optional.of(row.originalSyncId)
                : syncIdOf(row.originalId);
        if (series.isEmpty()) {
            // The series has not reached Peergos yet. It stays dirty until it has a name.
            Log.i(TAG, "Exception " + row.id + " waits for its series to be uploaded");
            return false;
        }
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, series.get());
        if (remote.isPresent() && row.originalInstanceTime != null) {
            String existing = new String(store.read(remote.get()), StandardCharsets.UTF_8);
            String excluded = ICalWriter.excludeOccurrence(existing, row.originalInstanceTime);
            if (! excluded.equals(existing))
                store.putObject(directory, series.get(), excluded.getBytes(StandardCharsets.UTF_8), remote);
        }
        if (! row.cancelled()) {
            String uid = UUID.randomUUID().toString();
            List<ICalWriter.Line> properties = ours(row);
            store.putObject(directory, uid + CalendarStore.ICS_SUFFIX,
                    withReminder(ICalWriter.create(uid, properties), row).getBytes(StandardCharsets.UTF_8),
                    Optional.empty());
        }
        purge(row.id);
        return true;
    }

    /** The name a series row was uploaded under, if it has been. */
    private Optional<String> syncIdOf(Long rowId) throws Exception {
        if (rowId == null)
            return Optional.empty();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._SYNC_ID},
                CalendarContract.Events._ID + "=?", new String[]{Long.toString(rowId)}, null)) {
            if (cursor == null || ! cursor.moveToFirst() || cursor.isNull(0))
                return Optional.empty();
            return Optional.of(cursor.getString(0));
        }
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
        write(directory, uid + CalendarStore.ICS_SUFFIX,
                withReminder(ICalWriter.create(uid, ours(row)), row), row.id, true);
        return true;
    }

    private boolean update(Row row, String directory) throws Exception {
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, row.syncId);
        if (remote.isEmpty()) {
            // Removed in Peergos while we were editing. Writing it back under the same name
            // resurrects the user's version rather than dropping their edit.
            write(directory, row.syncId,
                    withReminder(ICalWriter.create(uidFor(row), ours(row)), row), row.id, true);
            return true;
        }
        if (! remote.get().etag().equals(row.etag)) {
            duplicate(row, directory);
            purge(row.id);
            return true;
        }
        String existing = new String(store.read(remote.get()), StandardCharsets.UTF_8);
        // A row from before exclusions were mirrored has an EXDATE column that says nothing
        // about the file, so the file's own are left as they are.
        boolean faithful = CalendarMirror.FORMAT.equals(row.format);
        List<ICalWriter.Line> properties = properties(row);
        if (! faithful)
            properties.removeIf(line -> line.name.equals("EXDATE"));
        String patched = ICalWriter.patch(existing, properties,
                faithful ? List.of("EXDATE") : Collections.emptyList());
        write(directory, row.syncId, withReminder(patched, row), row.id, faithful);
        return true;
    }

    /** Writes the local version as a new event, leaving the remote one alone. */
    private void duplicate(Row row, String directory) throws Exception {
        String uid = UUID.randomUUID().toString();
        List<ICalWriter.Line> properties = ours(row);
        properties.add(ICalWriter.text("SUMMARY", row.title + " (edited on this device)"));
        store.putObject(directory, uid + CalendarStore.ICS_SUFFIX,
                withReminder(ICalWriter.create(uid, properties), row).getBytes(StandardCharsets.UTF_8),
                Optional.empty());
        Log.i(TAG, "Kept a conflicting local edit as " + uid);
    }

    /**
     * The reminder as the phone now has it. Without this a reminder added or cleared in
     * the platform calendar would never reach the file, and the next download pass — which
     * writes the Reminders row from the file — would quietly undo it.
     */
    private String withReminder(String ics, Row row) throws Exception {
        return ICalWriter.withReminder(ics, reminderOf(row.id), row.title);
    }

    /** The earliest alarm the phone would ring for this event, in minutes before the start. */
    private Optional<Integer> reminderOf(long rowId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Reminders.CONTENT_URI),
                new String[]{CalendarContract.Reminders.MINUTES},
                CalendarContract.Reminders.EVENT_ID + "=?",
                new String[]{Long.toString(rowId)},
                CalendarContract.Reminders.MINUTES + " DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                // A negative value is the provider's "use the calendar's default", which
                // names no time of its own to write down.
                if (! cursor.isNull(0) && cursor.getInt(0) >= 0)
                    return Optional.of(cursor.getInt(0));
            }
        }
        return Optional.empty();
    }

    /** @param faithful whether the row now says everything about exclusions the file does,
     *                  which is what marks it current for the download pass. */
    private void write(String directory, String name, String ics, long rowId, boolean faithful) throws Exception {
        // The ref the write hands back is where the new ETag comes from: reading it through
        // a listing instead would walk the whole shard again, having just invalidated it.
        AppDataStore.ObjectRef stored = store.putObject(directory, name,
                ics.getBytes(StandardCharsets.UTF_8), store.getObject(directory, name));
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events._SYNC_ID, name);
        values.put(CalendarContract.Events.SYNC_DATA1, stored.etag());
        values.put(CalendarContract.Events.DIRTY, 0);
        if (faithful)
            values.put(CalendarContract.Events.SYNC_DATA2, CalendarMirror.FORMAT);
        provider.update(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values,
                CalendarContract.Events._ID + "=?", new String[]{Long.toString(rowId)});
    }

    /** The UID already in the file if we can read it, so an update keeps the event's identity. */
    private String uidFor(Row row) {
        return row.syncId != null && row.syncId.endsWith(CalendarStore.ICS_SUFFIX)
                ? row.syncId.substring(0, row.syncId.length() - CalendarStore.ICS_SUFFIX.length())
                : UUID.randomUUID().toString();
    }

    /** Properties for an event this device is creating, which is one this account owns.
     *  The web calendar records the same thing, and the one it replaces will not let anyone
     *  edit an entry that names nobody. An update leaves whatever owner the file already has. */
    private List<ICalWriter.Line> ours(Row row) {
        List<ICalWriter.Line> lines = properties(row);
        lines.add(ICalWriter.text("X-OWNER", account.name));
        return lines;
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
        if (row.rrule != null) {
            lines.add(ICalWriter.raw("RRULE", row.rrule));
            List<Instant> excluded = Exdates.fromColumn(row.exdate);
            if (! excluded.isEmpty())
                lines.add(ICalWriter.exdates(excluded, row.allDay));
        }
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
        return ProviderUris.asSyncAdapter(uri, account);
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
        Long originalId;
        Long originalInstanceTime;
        int status;
        String exdate;
        String format;

        /** One occurrence of a series, changed or deleted on its own. */
        boolean isException() {
            return originalSyncId != null || originalId != null;
        }

        /** Deleted occurrence: a calendar app cancels it, or deletes an exception it made. */
        boolean cancelled() {
            return deleted || status == CalendarContract.Events.STATUS_CANCELED;
        }

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
            row.originalId = c.isNull(13) ? null : c.getLong(13);
            row.originalInstanceTime = c.isNull(14) ? null : c.getLong(14);
            row.status = c.isNull(15) ? CalendarContract.Events.STATUS_CONFIRMED : c.getInt(15);
            row.exdate = c.isNull(16) ? null : c.getString(16);
            row.format = c.isNull(17) ? null : c.getString(17);
            return row;
        }
    }
}
