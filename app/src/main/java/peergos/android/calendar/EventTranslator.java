package peergos.android.calendar;

import android.content.ContentValues;
import android.provider.CalendarContract;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;

/**
 * iCalendar to CalendarContract. The parser is the one the CalDAV bridge uses, compiled
 * into the app from the server jar, so the two surfaces cannot drift in how they read a
 * date or decide that an event recurs.
 *
 * Only the fields the web calendar app actually writes are mapped. Attendees live in their
 * own contract table and are left alone. Alarms are the exception: a reminder is the whole
 * point of mirroring for many people, so a VALARM becomes a Reminders row, which is why a
 * translation carries {@link Translation#reminderMinutes} beside the event row.
 *
 * A series brings its exclusions (EXDATE), which is how the web app records an occurrence
 * changed or deleted on its own. A CalDAV client may instead store a changed occurrence as a
 * second VEVENT in the same file, carrying a RECURRENCE-ID; each of those becomes an
 * {@link Translation#overrides override}, an exception row of the series in contract terms.
 */
public final class EventTranslator {

    private EventTranslator() {}

    /** An event row, the one alarm the Reminders table can hold for it, and for a series the
     *  occurrences a CalDAV client overrode within the same file. */
    public record Translation(ContentValues values, Optional<Integer> reminderMinutes, List<Override> overrides) {}

    /** One overridden occurrence: the start it replaces, and the row that replaces it. */
    public record Override(long instance, ContentValues values, Optional<Integer> reminderMinutes) {}

    /**
     * The contract rows for one calendar object, or empty if it carries nothing we can
     * place on a calendar — no start date, or not an event at all.
     */
    public static Optional<Translation> translate(String ics, long calendarId) {
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
        ICal.Component event = parts.stream()
                .filter(p -> p.name.equals("VEVENT") && p.property("RECURRENCE-ID").isEmpty())
                .findFirst()
                .orElse(parts.get(0));
        Optional<ContentValues> values = values(event, calendarId);
        if (values.isEmpty())
            return Optional.empty();
        boolean recurring = event.value("RRULE").isPresent();
        List<Override> overrides = new ArrayList<>();
        if (recurring) {
            boolean allDay = values.get().getAsInteger(CalendarContract.Events.ALL_DAY) == 1;
            for (ICal.Component part : parts) {
                if (part == event || ! part.name.equals("VEVENT"))
                    continue;
                Optional<Instant> instance = part.property("RECURRENCE-ID").flatMap(ICal::toInstant);
                if (instance.isEmpty())
                    continue;
                Optional<ContentValues> override = values(withoutRule(part), calendarId);
                if (override.isEmpty())
                    continue;
                override.get().put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, instance.get().toEpochMilli());
                override.get().put(CalendarContract.Events.ORIGINAL_ALL_DAY, allDay ? 1 : 0);
                overrides.add(new Override(instance.get().toEpochMilli(), override.get(), reminderMinutes(part)));
            }
        }
        return Optional.of(new Translation(values.get(), reminderMinutes(event), overrides));
    }

    /** The row for one VEVENT on its own. */
    private static Optional<ContentValues> values(ICal.Component event, long calendarId) {
        Optional<ICal.Property> start = event.property("DTSTART");
        if (start.isEmpty())
            return Optional.empty();
        Optional<Instant> from = ICal.toInstant(start.get());
        if (from.isEmpty())
            return Optional.empty();

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, unescape(event.value("SUMMARY").orElse("")));
        event.value("DESCRIPTION").ifPresent(d -> values.put(CalendarContract.Events.DESCRIPTION, unescape(d)));
        event.value("LOCATION").ifPresent(l -> values.put(CalendarContract.Events.EVENT_LOCATION, unescape(l)));
        values.put(CalendarContract.Events.DTSTART, from.get().toEpochMilli());
        values.put(CalendarContract.Events.STATUS, status(event));

        boolean allDay = isDate(start.get());
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        // An all-day event is stored against UTC midnight by the contract, whatever the
        // device's zone; anything else keeps the zone its DTSTART named.
        values.put(CalendarContract.Events.EVENT_TIMEZONE,
                allDay ? "UTC" : start.get().param("TZID").orElse("UTC"));

        values.put(CalendarContract.Events.HAS_ALARM, reminderMinutes(event).isPresent() ? 1 : 0);

        Optional<String> rrule = event.value("RRULE");
        if (rrule.isPresent()) {
            // The contract requires a duration rather than an end for a recurring event,
            // and rejects the row outright if both are set.
            values.put(CalendarContract.Events.RRULE, rrule.get());
            values.put(CalendarContract.Events.DURATION, duration(event, from.get(), allDay));
            // Written even when empty, so an exclusion removed in Peergos is removed here too.
            values.put(CalendarContract.Events.EXDATE, Exdates.toColumn(Exdates.of(event)));
        } else {
            values.put(CalendarContract.Events.DTEND, end(event, from.get(), allDay).toEpochMilli());
        }
        return Optional.of(values);
    }

    /** An override is one occurrence, even if the client that wrote it gave it a rule. */
    private static ICal.Component withoutRule(ICal.Component event) {
        List<ICal.Property> kept = new ArrayList<>();
        for (ICal.Property property : event.properties) {
            if (! property.name.equals("RRULE") && ! property.name.equals("EXDATE"))
                kept.add(property);
        }
        return new ICal.Component(event.name, kept, event.children);
    }

    /**
     * The alarm the phone can ring, read straight from a file. {@link #translate} works it
     * out as it goes; this is the same answer without a ContentValues in the way, which is
     * what makes the rule testable off a device.
     */
    public static Optional<Integer> reminderMinutes(String ics) {
        return ICal.parse(ics)
                .map(ICal.Component::scheduleComponents)
                .filter(parts -> ! parts.isEmpty())
                .flatMap(parts -> reminderMinutes(parts.get(0)));
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
     * How many minutes before the start this event's alarm rings, if it has one this
     * platform can show. Only a trigger measured back from the start counts: the
     * contract's Reminders table has no other shape — it takes minutes-before and nothing
     * else — so an absolute trigger, or one measured from the end, is left to whatever
     * wrote it rather than moved to a time it did not ask for. {@link ICalWriter} keeps to
     * the same rule when an alarm goes back the other way.
     */
    private static Optional<Integer> reminderMinutes(ICal.Component event) {
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
            if (minutes >= 0 && minutes <= Integer.MAX_VALUE)
                return Optional.of((int) minutes);
        }
        return Optional.empty();
    }

    /** DTEND, or DTSTART plus DURATION, or a sensible default when the event gives neither. */
    private static Instant end(ICal.Component event, Instant start, boolean allDay) {
        Optional<Instant> explicit = event.property("DTEND").flatMap(ICal::toInstant);
        if (explicit.isPresent())
            return explicit.get();
        Optional<Duration> length = event.value("DURATION").flatMap(ICal::parseDuration);
        if (length.isPresent())
            return start.plus(length.get());
        return allDay ? start.plus(Duration.ofDays(1)) : start.plus(Duration.ofHours(1));
    }

    private static String duration(ICal.Component event, Instant start, boolean allDay) {
        Optional<String> explicit = event.value("DURATION");
        if (explicit.isPresent())
            return explicit.get();
        Duration length = Duration.between(start, end(event, start, allDay));
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
