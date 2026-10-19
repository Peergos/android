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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.CalendarStore;
import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

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
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.EVENT_COLOR,
            CalendarContract.Events.EVENT_COLOR_KEY,
            CalendarContract.Events.AVAILABILITY,
            CalendarContract.Events.ACCESS_LEVEL,
            CalendarContract.Events.ORGANIZER,
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
            String excluded = ICalWriter.excludeOccurrence(existing, row.originalInstanceTime, ZoneId.systemDefault());
            if (! excluded.equals(existing))
                store.putObject(directory, series.get(), excluded.getBytes(StandardCharsets.UTF_8), remote);
        }
        if (! row.cancelled()) {
            String uid = UUID.randomUUID().toString();
            store.putObject(directory, uid + CalendarStore.ICS_SUFFIX,
                    fresh(uid, row, Optional.empty()).getBytes(StandardCharsets.UTF_8), Optional.empty());
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
        write(directory, uid + CalendarStore.ICS_SUFFIX, fresh(uid, row, Optional.empty()), row.id, true);
        return true;
    }

    private boolean update(Row row, String directory) throws Exception {
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, row.syncId);
        if (remote.isEmpty()) {
            // Removed in Peergos while we were editing. Writing it back under the same name
            // resurrects the user's version rather than dropping their edit.
            write(directory, row.syncId, fresh(uidFor(row), row, Optional.empty()), row.id, true);
            return true;
        }
        if (! remote.get().etag().equals(row.etag)) {
            duplicate(row, directory);
            purge(row.id);
            return true;
        }
        String existing = new String(store.read(remote.get()), StandardCharsets.UTF_8);
        // A row from before the mirror carried everything it now does has columns that say
        // nothing about the file, so what they would write is left as the file has it.
        boolean faithful = CalendarMirror.FORMAT.equals(row.format);
        Changes changes = changes(row, Existing.of(existing), faithful);
        String patched = ICalWriter.patch(existing, changes.lines, changes.removals);
        if (changes.define.isPresent())
            patched = ICalWriter.withTimeZone(patched, changes.define.get());
        write(directory, row.syncId, withReminder(patched, row), row.id, faithful);
        return true;
    }

    /** Writes the local version as a new event, leaving the remote one alone. */
    private void duplicate(Row row, String directory) throws Exception {
        String uid = UUID.randomUUID().toString();
        store.putObject(directory, uid + CalendarStore.ICS_SUFFIX,
                fresh(uid, row, Optional.of(row.title + " (edited on this device)")).getBytes(StandardCharsets.UTF_8),
                Optional.empty());
        Log.i(TAG, "Kept a conflicting local edit as " + uid);
    }

    /**
     * A whole file for an event this device is creating, which is one this account owns.
     * The web calendar records the same thing, and the one it replaces will not let anyone
     * edit an entry that names nobody. An update leaves whatever owner the file already has.
     */
    private String fresh(String uid, Row row, Optional<String> title) throws Exception {
        Changes changes = changes(row, Optional.empty(), true);
        List<ICalWriter.Line> lines = new ArrayList<>(changes.lines);
        title.ifPresent(t -> lines.add(ICalWriter.text("SUMMARY", t)));
        lines.add(ICalWriter.text("X-OWNER", account.name));
        String ics = withReminder(ICalWriter.create(uid, lines), row);
        return changes.define.map(zone -> ICalWriter.withTimeZone(ics, zone)).orElse(ics);
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

    /** What an upload writes into the file, and what it takes out. */
    private record Changes(List<ICalWriter.Line> lines, List<String> removals, Optional<ZoneId> define) {}

    /** The file as it is in Peergos, and what the download pass makes of it. */
    private record Existing(ICal.Component event, ICal.Component vcalendar, EventTranslator.Translation mapped) {
        static Optional<Existing> of(String ics) {
            Optional<ICal.Component> parsed = ICal.parse(ics);
            Optional<EventTranslator.Translation> mapped = EventTranslator.translate(ics, 0, ZoneId.systemDefault());
            if (parsed.isEmpty() || mapped.isEmpty())
                return Optional.empty();
            Optional<ICal.Component> event = parsed.get().scheduleComponents().stream()
                    .filter(c -> c.name.equals("VEVENT") && c.property("RECURRENCE-ID").isEmpty())
                    .findFirst();
            return event.map(e -> new Existing(e, parsed.get(), mapped.get()));
        }

        ContentValues values() {
            return mapped.values();
        }
    }

    /**
     * The properties the platform calendar can express, as they now stand on the phone.
     *
     * Against a file that already exists, a group of them is written only if the row says
     * something different from what the download pass reads out of the file: a field the
     * phone did not change is left exactly as the file has it, in whatever form it is
     * written — a Windows zone name, a floating time, an attendee's RSVP. DTEND is always
     * written with a start, even for a recurring event where the contract holds a duration
     * instead, because the web calendar app treats an event without one as incomplete.
     */
    private Changes changes(Row row, Optional<Existing> existing, boolean faithful) throws Exception {
        List<ICalWriter.Line> lines = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        Optional<ZoneId> define = Optional.empty();
        Optional<ContentValues> before = existing.map(Existing::values);

        lines.add(ICalWriter.text("SUMMARY", row.title == null ? "" : row.title));
        if (row.description != null)
            lines.add(ICalWriter.text("DESCRIPTION", row.description));
        if (row.location != null)
            lines.add(ICalWriter.text("LOCATION", row.location));

        ICalWriter.Form form = form(row, existing);
        if (before.isEmpty() || ! sameTimes(row, before.get(), faithful)) {
            long end = endOf(row);
            if (row.allDay) {
                lines.add(ICalWriter.date("DTSTART", row.start));
                lines.add(ICalWriter.date("DTEND", Math.max(end, row.start + Duration.ofDays(1).toMillis())));
            } else {
                lines.add(form.line("DTSTART", Instant.ofEpochMilli(row.start)));
                lines.add(form.line("DTEND", Instant.ofEpochMilli(end)));
                if (form.tzid.isPresent() && form.tzid.get().equals(form.zone.getId()))
                    define = Optional.of(form.zone);
            }
            removals.add("DURATION");
        }

        String rruleBefore = before.map(v -> v.getAsString(CalendarContract.Events.RRULE)).orElse(null);
        if (row.rrule != null && ! row.rrule.equals(rruleBefore))
            lines.add(ICalWriter.raw("RRULE", row.rrule));
        else if (row.rrule == null && rruleBefore != null)
            removals.add("RRULE");

        if (! faithful)
            return new Changes(lines, removals, define);

        dates("EXDATE", CalendarContract.Events.EXDATE, row.exdate, row.rrule != null || row.rdate != null,
                Optional.empty(), before, form, lines, removals);
        // A series of listed dates alone has its start among them on the phone; in the file
        // DTSTART is that one already.
        Optional<Long> start = row.rrule == null ? Optional.of(row.start) : Optional.empty();
        dates("RDATE", CalendarContract.Events.RDATE, row.rdate, true, start, before, form, lines, removals);

        int status = before.map(v -> v.getAsInteger(CalendarContract.Events.STATUS)).orElse(-1);
        if (row.status != status)
            lines.add(ICalWriter.raw("STATUS", status(row.status)));

        int busy = row.availability == CalendarContract.Events.AVAILABILITY_FREE
                ? CalendarContract.Events.AVAILABILITY_FREE : CalendarContract.Events.AVAILABILITY_BUSY;
        if (before.isEmpty() || busy != before.get().getAsInteger(CalendarContract.Events.AVAILABILITY))
            lines.add(ICalWriter.raw("TRANSP", busy == CalendarContract.Events.AVAILABILITY_FREE ? "TRANSPARENT" : "OPAQUE"));

        int access = before.map(v -> v.getAsInteger(CalendarContract.Events.ACCESS_LEVEL))
                .orElse(CalendarContract.Events.ACCESS_DEFAULT);
        if (row.accessLevel != access) {
            Optional<String> value = accessClass(row.accessLevel);
            if (value.isPresent())
                lines.add(ICalWriter.raw("CLASS", value.get()));
            else
                removals.add("CLASS");
        }

        Integer colour = row.colourKey != null ? Colours.parse(row.colourKey).orElse(row.colour) : row.colour;
        Integer colourBefore = before.map(v -> v.getAsInteger(CalendarContract.Events.EVENT_COLOR)).orElse(null);
        if (! java.util.Objects.equals(colour, colourBefore)) {
            if (colour == null)
                removals.add("COLOR");
            else
                lines.add(ICalWriter.raw("COLOR", row.colourKey != null ? row.colourKey : Colours.nameOf(colour)));
        }

        List<EventTranslator.Attendee> attendees = attendeesOf(row.id);
        List<EventTranslator.Attendee> attendeesBefore = existing.map(e -> e.mapped().attendees()).orElse(List.of());
        if (! sameAttendees(attendees, attendeesBefore)) {
            if (attendees.isEmpty())
                removals.add("ATTENDEE");
            for (EventTranslator.Attendee attendee : attendees)
                lines.add(attendeeLine(attendee, existing.map(Existing::event)));
        }
        // An organiser is only ever named for an event made here, and only if the phone has
        // an address for it: the provider fills in the calendar's owner, a Peergos username.
        if (existing.isEmpty() && ! attendees.isEmpty() && row.organizer != null && row.organizer.contains("@"))
            lines.add(ICalWriter.raw("ORGANIZER", "mailto:" + row.organizer));

        lines.add(ICalWriter.timestamp("LAST-MODIFIED", System.currentTimeMillis()));
        lines.add(ICalWriter.timestamp("DTSTAMP", System.currentTimeMillis()));
        return new Changes(lines, removals, define);
    }

    /**
     * How the row's times are written. All-day is a date and UTC is UTC. Otherwise the
     * file's own form is kept if it reads as the same zone — its TZID, even one only its
     * VTIMEZONE defines, or a floating time read in the phone's zone — and a zone it does
     * not name is written as its IANA name.
     */
    private static ICalWriter.Form form(Row row, Optional<Existing> existing) {
        if (row.allDay)
            return ICalWriter.Form.date();
        ZoneId zone = zoneOf(row.timezone);
        if (zone.normalized().equals(ZoneOffset.UTC))
            return ICalWriter.Form.utc();
        if (existing.isPresent()) {
            Optional<ICal.Property> start = existing.get().event().property("DTSTART");
            if (start.isPresent()) {
                ICalWriter.Form current = ICalWriter.Form.of(start.get(), TimeZones.of(existing.get().vcalendar()),
                        ZoneId.systemDefault());
                if (! current.date && ! current.isUtc() && current.zone.getId().equals(zone.getId()))
                    return current;
            }
        }
        return ICalWriter.Form.zoned(zone.getId(), zone);
    }

    private static ZoneId zoneOf(String timezone) {
        try {
            return timezone == null ? ZoneOffset.UTC : ZoneId.of(timezone);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** Whether the row is at the same times the file is, in the same zone. A row written
     *  before zones were mirrored kept a zone that says nothing, so only its times count. */
    private static boolean sameTimes(Row row, ContentValues before, boolean faithful) {
        Long start = before.getAsLong(CalendarContract.Events.DTSTART);
        boolean allDay = before.getAsInteger(CalendarContract.Events.ALL_DAY) == 1;
        if (start == null || start != row.start || allDay != row.allDay || endOf(before) != endOf(row))
            return false;
        return ! faithful || row.allDay
                || zoneOf(row.timezone).getId().equals(zoneOf(before.getAsString(CalendarContract.Events.EVENT_TIMEZONE)).getId());
    }

    private static long endOf(ContentValues values) {
        Long end = values.getAsLong(CalendarContract.Events.DTEND);
        if (end != null)
            return end;
        long start = values.getAsLong(CalendarContract.Events.DTSTART);
        return start + Optional.ofNullable(values.getAsString(CalendarContract.Events.DURATION))
                .flatMap(ICal::parseDuration).map(Duration::toMillis).orElse(0L);
    }

    /** EXDATE or RDATE: written in the start's form if they differ from the file's, and taken
     *  out if the row has none. */
    private static void dates(String name, String column, String value, boolean recurring, Optional<Long> start,
                              Optional<ContentValues> before, ICalWriter.Form form, List<ICalWriter.Line> lines,
                              List<String> removals) {
        List<Instant> now = new ArrayList<>(recurring ? Exdates.fromColumn(value) : List.of());
        start.ifPresent(s -> now.remove(Instant.ofEpochMilli(s)));
        List<Instant> was = new ArrayList<>(before.map(v -> Exdates.fromColumn(v.getAsString(column))).orElse(List.of()));
        start.ifPresent(s -> was.remove(Instant.ofEpochMilli(s)));
        if (new HashSet<>(now).equals(new HashSet<>(was)))
            return;
        if (now.isEmpty())
            removals.add(name);
        else
            lines.add(form.list(name, now));
    }

    private static String status(int status) {
        if (status == CalendarContract.Events.STATUS_CANCELED)
            return "CANCELLED";
        return status == CalendarContract.Events.STATUS_TENTATIVE ? "TENTATIVE" : "CONFIRMED";
    }

    private static Optional<String> accessClass(int accessLevel) {
        switch (accessLevel) {
            case CalendarContract.Events.ACCESS_PUBLIC: return Optional.of("PUBLIC");
            case CalendarContract.Events.ACCESS_PRIVATE: return Optional.of("PRIVATE");
            case CalendarContract.Events.ACCESS_CONFIDENTIAL: return Optional.of("CONFIDENTIAL");
            default: return Optional.empty();
        }
    }

    /** The same people, named the same, in the same roles and with the same answers. */
    private static boolean sameAttendees(List<EventTranslator.Attendee> a, List<EventTranslator.Attendee> b) {
        return new HashSet<>(comparable(a)).equals(new HashSet<>(comparable(b)));
    }

    private static List<List<Object>> comparable(List<EventTranslator.Attendee> attendees) {
        List<List<Object>> out = new ArrayList<>();
        for (EventTranslator.Attendee a : attendees)
            out.add(java.util.Arrays.asList(a.email().toLowerCase(Locale.ROOT), a.name() == null || a.name().isEmpty() ? null : a.name(),
                    a.type(), a.status() == CalendarContract.Attendees.ATTENDEE_STATUS_NONE
                            ? CalendarContract.Attendees.ATTENDEE_STATUS_INVITED : a.status()));
        return out;
    }

    /**
     * One ATTENDEE line. Someone the file already lists keeps their line's parameters,
     * RSVP and delegation among them, with only what the phone changed replaced.
     */
    static ICalWriter.Line attendeeLine(EventTranslator.Attendee attendee, Optional<ICal.Component> event) {
        Optional<ICal.Property> listed = event.flatMap(e -> e.properties.stream()
                .filter(p -> p.name.equals("ATTENDEE") && EventTranslator.email(p).equalsIgnoreCase(attendee.email()))
                .findFirst());
        Map<String, String> params = new LinkedHashMap<>(listed.map(p -> p.params).orElse(Map.of()));
        String name = attendee.name() == null || attendee.name().isEmpty() ? null : attendee.name();
        if (! java.util.Objects.equals(params.get("CN"), name)) {
            if (name == null)
                params.remove("CN");
            else
                params.put("CN", name);
        }
        if (listed.isEmpty() || EventTranslator.attendeeType(listed.get()) != attendee.type()) {
            if (attendee.type() == CalendarContract.Attendees.TYPE_RESOURCE) {
                params.put("CUTYPE", "RESOURCE");
            } else {
                if ("RESOURCE".equalsIgnoreCase(params.get("CUTYPE")) || "ROOM".equalsIgnoreCase(params.get("CUTYPE")))
                    params.put("CUTYPE", "INDIVIDUAL");
                params.put("ROLE", attendee.type() == CalendarContract.Attendees.TYPE_OPTIONAL ? "OPT-PARTICIPANT"
                        : attendee.type() == CalendarContract.Attendees.TYPE_NONE ? "NON-PARTICIPANT" : "REQ-PARTICIPANT");
            }
        }
        if (listed.isEmpty() || EventTranslator.attendeeStatus(listed.get()) != attendee.status())
            params.put("PARTSTAT", partstat(attendee.status()));
        StringBuilder rest = new StringBuilder();
        for (Map.Entry<String, String> param : params.entrySet())
            rest.append(';').append(param.getKey()).append('=').append(ICalWriter.paramValue(param.getValue()));
        rest.append(':').append(listed.map(p -> p.value).orElse("mailto:" + attendee.email()));
        return new ICalWriter.Line("ATTENDEE", rest.toString());
    }

    private static String partstat(int status) {
        switch (status) {
            case CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED: return "ACCEPTED";
            case CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED: return "DECLINED";
            case CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE: return "TENTATIVE";
            default: return "NEEDS-ACTION";
        }
    }

    /** The people the phone has on this event, those with an address to write: a calendar
     *  app lists the organiser by the calendar's owner, which is a username, not an address. */
    private List<EventTranslator.Attendee> attendeesOf(long rowId) throws Exception {
        List<EventTranslator.Attendee> out = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Attendees.CONTENT_URI),
                new String[]{CalendarContract.Attendees.ATTENDEE_EMAIL, CalendarContract.Attendees.ATTENDEE_NAME,
                        CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.ATTENDEE_TYPE,
                        CalendarContract.Attendees.ATTENDEE_STATUS},
                CalendarContract.Attendees.EVENT_ID + "=?", new String[]{Long.toString(rowId)}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                String email = cursor.isNull(0) ? "" : cursor.getString(0);
                if (! email.contains("@"))
                    continue;
                out.add(new EventTranslator.Attendee(email, cursor.isNull(1) ? null : cursor.getString(1),
                        cursor.getInt(2), cursor.getInt(3), cursor.getInt(4)));
            }
        }
        return out;
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
        String timezone;
        String rdate;
        Integer colour;
        String colourKey;
        int availability;
        int accessLevel;
        String organizer;

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
            row.timezone = c.isNull(18) ? null : c.getString(18);
            row.rdate = c.isNull(19) ? null : c.getString(19);
            row.colour = c.isNull(20) ? null : c.getInt(20);
            row.colourKey = c.isNull(21) ? null : c.getString(21);
            row.availability = c.isNull(22) ? CalendarContract.Events.AVAILABILITY_BUSY : c.getInt(22);
            row.accessLevel = c.isNull(23) ? CalendarContract.Events.ACCESS_DEFAULT : c.getInt(23);
            row.organizer = c.isNull(24) ? null : c.getString(24);
            return row;
        }
    }
}
