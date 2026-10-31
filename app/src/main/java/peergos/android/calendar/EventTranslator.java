package peergos.android.calendar;

import android.content.ContentValues;
import android.provider.CalendarContract;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

/**
 * iCalendar to CalendarContract. The parser is the one the CalDAV bridge uses, compiled
 * into the app from the server jar, so the two surfaces cannot drift in how they read a
 * date or decide that an event recurs.
 *
 * Alarms become Reminders rows, which is why a translation carries
 * {@link Translation#reminders} beside the event row, and attendees become rows of
 * the Attendees table, which is why it carries those too.
 *
 * A series brings its exclusions (EXDATE), which is how the web app records an occurrence
 * changed or deleted on its own, and any dates it adds to its rule (RDATE). A CalDAV client
 * may instead store a changed occurrence as a second VEVENT in the same file, carrying a
 * RECURRENCE-ID; each of those becomes an {@link Translation#overrides override}, an
 * exception row of the series in contract terms.
 *
 * Times are read as the web app reads them: a TZID by its IANA name, a Windows name or the
 * file's own VTIMEZONE, and a floating time in the phone's own zone.
 */
public final class EventTranslator {

    private EventTranslator() {}

    /** An event row, the alarms the Reminders table can hold for it, its attendees, and
     *  for a series the occurrences a CalDAV client overrode within the same file. */
    public record Translation(ContentValues values, List<Integer> reminders, List<Attendee> attendees,
                              List<Override> overrides) {}

    /** One overridden occurrence: the start it replaces, and the row that replaces it. */
    public record Override(long instance, ContentValues values, List<Integer> reminders,
                           List<Attendee> attendees) {}

    /** One ATTENDEE, as the contract's Attendees table holds it. */
    public record Attendee(String email, String name, int relationship, int type, int status) {}

    /**
     * The contract rows for one calendar object, or empty if it carries nothing we can
     * place on a calendar — no start date, or not an event at all.
     */
    public static Optional<Translation> translate(String ics, long calendarId) {
        return translate(ics, calendarId, ZoneId.systemDefault());
    }

    /** As {@link #translate(String, long)}, reading a floating time in {@code floating}. */
    public static Optional<Translation> translate(String ics, long calendarId, ZoneId floating) {
        Optional<ICal.Component> parsed = ICal.parse(ics);
        if (parsed.isEmpty())
            return Optional.empty();
        List<ICal.Component> parts = parsed.get().scheduleComponents();
        if (parts.isEmpty())
            return Optional.empty();
        // A calendar collection also holds tasks, which the contract has no table for, and
        // a VTODO with a DTSTART would otherwise map cleanly onto an event row and appear
        // on the phone as one.
        if (! parts.get(0).name.equals("VEVENT"))
            return Optional.empty();
        TimeZones zones = TimeZones.of(parsed.get());
        ICal.Component event = parts.stream()
                .filter(p -> p.name.equals("VEVENT") && p.property("RECURRENCE-ID").isEmpty())
                .findFirst()
                .orElse(parts.get(0));
        Optional<ContentValues> values = values(event, calendarId, zones, floating);
        if (values.isEmpty())
            return Optional.empty();
        boolean recurring = event.value("RRULE").isPresent() || event.value("RDATE").isPresent();
        List<Override> overrides = new ArrayList<>();
        if (recurring) {
            boolean allDay = values.get().getAsInteger(CalendarContract.Events.ALL_DAY) == 1;
            for (ICal.Component part : parts) {
                if (part == event || ! part.name.equals("VEVENT"))
                    continue;
                Optional<Instant> instance = part.property("RECURRENCE-ID")
                        .flatMap(p -> ICal.toInstant(p, zones, floating));
                if (instance.isEmpty())
                    continue;
                Optional<ContentValues> override = values(withoutRule(part), calendarId, zones, floating);
                if (override.isEmpty())
                    continue;
                override.get().put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instance.get().toEpochMilli());
                override.get().put(CalendarContract.Events.ORIGINAL_ALL_DAY, allDay ? 1 : 0);
                overrides.add(new Override(instance.get().toEpochMilli(), override.get(), reminders(part),
                        attendees(part)));
            }
        }
        return Optional.of(new Translation(values.get(), reminders(event), attendees(event), overrides));
    }

    /** The row for one VEVENT on its own. */
    private static Optional<ContentValues> values(ICal.Component event, long calendarId, TimeZones zones, ZoneId floating) {
        Optional<ICal.Property> start = event.property("DTSTART");
        if (start.isEmpty())
            return Optional.empty();
        Optional<Instant> from = ICal.toInstant(start.get(), zones, floating);
        if (from.isEmpty())
            return Optional.empty();

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, unescape(event.value("SUMMARY").orElse("")));
        // Null when the file has none: the row is updated in place, and a column left out of
        // the update keeps the text another client has since cleared.
        values.put(CalendarContract.Events.DESCRIPTION, event.value("DESCRIPTION").map(EventTranslator::unescape).orElse(null));
        values.put(CalendarContract.Events.EVENT_LOCATION, event.value("LOCATION").map(EventTranslator::unescape).orElse(null));
        values.put(CalendarContract.Events.DTSTART, from.get().toEpochMilli());
        values.put(CalendarContract.Events.STATUS, status(event));
        values.put(CalendarContract.Events.AVAILABILITY, availability(event));
        values.put(CalendarContract.Events.ACCESS_LEVEL, accessLevel(event));
        colour(event, values);
        // With none, the provider makes the calendar's owner the organiser.
        Optional<String> organizer = event.property("ORGANIZER").map(EventTranslator::email);
        if (organizer.isPresent())
            values.put(CalendarContract.Events.ORGANIZER, organizer.get());
        values.put(CalendarContract.Events.HAS_ATTENDEE_DATA, 1);

        boolean allDay = isDate(start.get());
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, timezone(start.get(), allDay, zones, floating));

        values.put(CalendarContract.Events.HAS_ALARM, reminders(event).isEmpty() ? 0 : 1);

        Optional<String> rrule = event.value("RRULE");
        List<Instant> rdates = new ArrayList<>(Exdates.of(event, "RDATE", zones, floating));
        // RFC 5545 counts DTSTART as the first occurrence; the provider counts it only
        // through a rule, so a series that is nothing but listed dates lists its start too.
        if (rrule.isEmpty() && ! rdates.isEmpty() && ! rdates.contains(from.get()))
            rdates.add(0, from.get());
        if (rrule.isPresent() || ! rdates.isEmpty()) {
            // The contract requires a duration rather than an end for a recurring event,
            // and rejects the row outright if both are set.
            values.put(CalendarContract.Events.RRULE, rrule.orElse(null));
            values.put(CalendarContract.Events.RDATE, Exdates.toColumn(rdates));
            values.put(CalendarContract.Events.DURATION, duration(event, from.get(), allDay, zones, floating));
            // Written even when empty, so an exclusion removed in Peergos is removed here too.
            values.put(CalendarContract.Events.EXDATE, Exdates.toColumn(Exdates.of(event, "EXDATE", zones, floating)));
            values.putNull(CalendarContract.Events.DTEND);
        } else {
            // Cleared rather than left out, so a series that stops repeating stops here too.
            for (String column : List.of(CalendarContract.Events.RRULE, CalendarContract.Events.RDATE,
                    CalendarContract.Events.EXDATE, CalendarContract.Events.DURATION))
                values.putNull(column);
            values.put(CalendarContract.Events.DTEND, end(event, from.get(), allDay, zones, floating).toEpochMilli());
        }
        return Optional.of(values);
    }

    /**
     * The zone the contract expands a series in and shows the event against. An all-day
     * event is kept at UTC midnight; a time in UTC is in UTC; a named zone is the IANA zone
     * it resolves to; and a floating time, or a zone nothing can place, is the phone's own,
     * which is where it was read.
     */
    static String timezone(ICal.Property start, boolean allDay, TimeZones zones, ZoneId floating) {
        if (allDay || start.value.trim().endsWith("Z"))
            return "UTC";
        return start.param("TZID").flatMap(zones::zone).orElse(floating).getId();
    }

    /** An override is one occurrence, even if the client that wrote it gave it a rule. */
    private static ICal.Component withoutRule(ICal.Component event) {
        List<ICal.Property> kept = new ArrayList<>();
        for (ICal.Property property : event.properties) {
            if (! property.name.equals("RRULE") && ! property.name.equals("EXDATE") && ! property.name.equals("RDATE"))
                kept.add(property);
        }
        return new ICal.Component(event.name, kept, event.children);
    }

    /** TRANSP: an event marked transparent leaves its time free. */
    static int availability(ICal.Component event) {
        return event.value("TRANSP").map(t -> t.trim().equalsIgnoreCase("TRANSPARENT")).orElse(false)
                ? CalendarContract.Events.AVAILABILITY_FREE : CalendarContract.Events.AVAILABILITY_BUSY;
    }

    /** CLASS, of which the contract has the three RFC 5545 values; anything else is the default. */
    static int accessLevel(ICal.Component event) {
        switch (event.value("CLASS").map(c -> c.trim().toUpperCase(Locale.ROOT)).orElse("")) {
            case "PUBLIC": return CalendarContract.Events.ACCESS_PUBLIC;
            case "PRIVATE": return CalendarContract.Events.ACCESS_PRIVATE;
            case "CONFIDENTIAL": return CalendarContract.Events.ACCESS_CONFIDENTIAL;
            default: return CalendarContract.Events.ACCESS_DEFAULT;
        }
    }

    /** COLOR, under its palette key if it has one, so the phone's colour picker shows it picked. */
    private static void colour(ICal.Component event, ContentValues values) {
        Optional<String> named = event.value("COLOR");
        Optional<Integer> argb = named.flatMap(Colours::parse);
        Optional<String> key = named.flatMap(Colours::paletteKey);
        if (key.isPresent())
            values.put(CalendarContract.Events.EVENT_COLOR_KEY, key.get());
        else
            values.putNull(CalendarContract.Events.EVENT_COLOR_KEY);
        if (argb.isPresent())
            values.put(CalendarContract.Events.EVENT_COLOR, argb.get());
        else
            values.putNull(CalendarContract.Events.EVENT_COLOR);
    }

    /**
     * Every ATTENDEE with an address. The organiser is told apart by matching ORGANIZER,
     * and is otherwise an attendee like the rest: listing it is the file's choice, and an
     * organiser the file does not also list as attending is not made into one here.
     */
    static List<Attendee> attendees(ICal.Component event) {
        Optional<String> organizer = event.property("ORGANIZER").map(EventTranslator::email);
        List<Attendee> out = new ArrayList<>();
        for (ICal.Property attendee : event.properties) {
            if (! attendee.name.equals("ATTENDEE"))
                continue;
            String email = email(attendee);
            if (email.isEmpty())
                continue;
            int relationship = organizer.map(o -> o.equalsIgnoreCase(email)).orElse(false)
                    ? CalendarContract.Attendees.RELATIONSHIP_ORGANIZER : CalendarContract.Attendees.RELATIONSHIP_ATTENDEE;
            out.add(new Attendee(email, attendee.param("CN").orElse(null), relationship,
                    attendeeType(attendee), attendeeStatus(attendee)));
        }
        return out;
    }

    /** A calendar user address, the mailto: of which is all the contract keeps. */
    static String email(ICal.Property address) {
        String value = address.value.trim();
        return value.regionMatches(true, 0, "mailto:", 0, 7) ? value.substring(7) : value;
    }

    /** CUTYPE and ROLE together: a room or other resource, or how much the person is needed. */
    static int attendeeType(ICal.Property attendee) {
        String cutype = attendee.param("CUTYPE").orElse("").toUpperCase(Locale.ROOT);
        if (cutype.equals("RESOURCE") || cutype.equals("ROOM"))
            return CalendarContract.Attendees.TYPE_RESOURCE;
        switch (attendee.param("ROLE").orElse("REQ-PARTICIPANT").toUpperCase(Locale.ROOT)) {
            case "OPT-PARTICIPANT": return CalendarContract.Attendees.TYPE_OPTIONAL;
            case "NON-PARTICIPANT": return CalendarContract.Attendees.TYPE_NONE;
            default: return CalendarContract.Attendees.TYPE_REQUIRED;
        }
    }

    static int attendeeStatus(ICal.Property attendee) {
        switch (attendee.param("PARTSTAT").orElse("NEEDS-ACTION").toUpperCase(Locale.ROOT)) {
            case "ACCEPTED": return CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED;
            case "DECLINED": return CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED;
            case "TENTATIVE": return CalendarContract.Attendees.ATTENDEE_STATUS_TENTATIVE;
            case "NEEDS-ACTION": return CalendarContract.Attendees.ATTENDEE_STATUS_INVITED;
            default: return CalendarContract.Attendees.ATTENDEE_STATUS_NONE;
        }
    }

    /**
     * The alarms the phone can ring, read straight from a file. {@link #translate} works
     * them out as it goes; this is the same answer without a ContentValues in the way, which
     * is what makes the rule testable off a device.
     */
    public static List<Integer> reminders(String ics) {
        return ICal.parse(ics)
                .map(ICal.Component::scheduleComponents)
                .filter(parts -> ! parts.isEmpty())
                .map(parts -> reminders(parts.get(0)))
                .orElse(List.of());
    }

    /** The three states the contract shares with iCalendar. */
    private static int status(ICal.Component event) {
        String status = event.value("STATUS").orElse("").toUpperCase(Locale.ROOT);
        if (status.equals("CANCELLED"))
            return CalendarContract.Events.STATUS_CANCELED;
        return status.equals("TENTATIVE")
                ? CalendarContract.Events.STATUS_TENTATIVE
                : CalendarContract.Events.STATUS_CONFIRMED;
    }

    /**
     * How many minutes before the start this event's alarms ring, each one this platform
     * can show, in the file's order. Only a trigger measured back from the start counts: the
     * contract's Reminders table has no other shape — it takes minutes-before and nothing
     * else — so an absolute trigger, or one measured from the end, is left to whatever
     * wrote it rather than moved to a time it did not ask for. {@link ICalWriter} keeps to
     * the same rule when the alarms go back the other way.
     */
    private static List<Integer> reminders(ICal.Component event) {
        List<Integer> out = new ArrayList<>();
        for (ICal.Component alarm : event.children("VALARM")) {
            Optional<ICal.Property> trigger = alarm.property("TRIGGER");
            if (trigger.isEmpty())
                continue;
            ICal.Property property = trigger.get();
            if (! ICalWriter.isMinutesBeforeStart(property.value,
                    property.param("VALUE").orElse(null), property.param("RELATED").orElse(null)))
                continue;
            Optional<Duration> before = ICal.parseDuration(property.value.substring(1));
            if (before.isEmpty())
                continue;
            long minutes = before.get().toMinutes();
            if (minutes >= 0 && minutes <= Integer.MAX_VALUE && ! out.contains((int) minutes))
                out.add((int) minutes);
        }
        return out;
    }

    /** DTEND, or DTSTART plus DURATION, or a sensible default when the event gives neither. */
    private static Instant end(ICal.Component event, Instant start, boolean allDay, TimeZones zones, ZoneId floating) {
        Optional<Instant> explicit = event.property("DTEND").flatMap(p -> ICal.toInstant(p, zones, floating));
        if (explicit.isPresent())
            return explicit.get();
        Optional<Duration> length = event.value("DURATION").flatMap(ICal::parseDuration);
        if (length.isPresent())
            return start.plus(length.get());
        return allDay ? start.plus(Duration.ofDays(1)) : start.plus(Duration.ofHours(1));
    }

    private static String duration(ICal.Component event, Instant start, boolean allDay, TimeZones zones, ZoneId floating) {
        Optional<String> explicit = event.value("DURATION");
        if (explicit.isPresent())
            return explicit.get();
        Duration length = Duration.between(start, end(event, start, allDay, zones, floating));
        if (allDay)
            return "P" + Math.max(1, length.toDays()) + "D";
        return "PT" + Math.max(1, length.toMinutes()) + "M";
    }

    /** A DATE value means an all-day event; a DATE-TIME does not. */
    private static boolean isDate(ICal.Property start) {
        return start.param("VALUE").map(v -> v.equalsIgnoreCase("DATE")).orElse(false)
                || start.value.trim().length() == 8;
    }

    /** RFC 5545 escapes commas, semicolons and newlines in text values. */
    private static String unescape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 == text.length()) {
                out.append(c);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case 'n': case 'N': out.append('\n'); break;
                default: out.append(next);
            }
        }
        return out.toString();
    }
}
