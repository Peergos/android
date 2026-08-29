package peergos.android.calendar;

import android.content.ContentValues;
import android.provider.CalendarContract;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;

/**
 * iCalendar to CalendarContract. The parser is the one the CalDAV bridge uses, compiled
 * into the app from the server jar, so the two surfaces cannot drift in how they read a
 * date or decide that an event recurs.
 *
 * Only the fields the web calendar app actually writes are mapped. Attendees, alarms and
 * exceptions to recurring events live in their own contract tables and are left for the
 * write path, which is where they start to matter.
 */
public final class EventTranslator {

    private EventTranslator() {}

    /**
     * The contract row for one calendar object, or empty if it carries nothing we can
     * place on a calendar — no start date, or not an event at all.
     */
    public static Optional<ContentValues> toEvent(String ics, long calendarId) {
        Optional<ICal.Component> parsed = ICal.parse(ics);
        if (parsed.isEmpty())
            return Optional.empty();
        List<ICal.Component> parts = parsed.get().scheduleComponents();
        if (parts.isEmpty())
            return Optional.empty();
        ICal.Component event = parts.get(0);
        Optional<ICal.Property> start = event.property("DTSTART");
        if (start.isEmpty())
            return Optional.empty();
        Optional<Instant> from = ICal.toInstant(start.get());
        if (from.isEmpty())
            return Optional.empty();

        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, event.value("SUMMARY").orElse(""));
        event.value("DESCRIPTION").ifPresent(d -> values.put(CalendarContract.Events.DESCRIPTION, unescape(d)));
        event.value("LOCATION").ifPresent(l -> values.put(CalendarContract.Events.EVENT_LOCATION, unescape(l)));
        values.put(CalendarContract.Events.DTSTART, from.get().toEpochMilli());

        boolean allDay = isDate(start.get());
        values.put(CalendarContract.Events.ALL_DAY, allDay ? 1 : 0);
        // An all-day event is stored against UTC midnight by the contract, whatever the
        // device's zone; anything else keeps the zone its DTSTART named.
        values.put(CalendarContract.Events.EVENT_TIMEZONE,
                allDay ? "UTC" : start.get().param("TZID").orElse("UTC"));

        Optional<String> rrule = event.value("RRULE");
        if (rrule.isPresent()) {
            // The contract requires a duration rather than an end for a recurring event,
            // and rejects the row outright if both are set.
            values.put(CalendarContract.Events.RRULE, rrule.get());
            values.put(CalendarContract.Events.DURATION, duration(event, from.get(), allDay));
        } else {
            values.put(CalendarContract.Events.DTEND, end(event, from.get(), allDay).toEpochMilli());
        }
        return Optional.of(values);
    }

    /** DTEND, or DTSTART plus DURATION, or a sensible default when the event gives neither. */
    private static Instant end(ICal.Component event, Instant start, boolean allDay) {
        Optional<Instant> explicit = event.property("DTEND").flatMap(ICal::toInstant);
        if (explicit.isPresent())
            return explicit.get();
        Optional<java.time.Duration> length = event.value("DURATION").flatMap(ICal::parseDuration);
        if (length.isPresent())
            return start.plus(length.get());
        return allDay ? start.plus(java.time.Duration.ofDays(1)) : start.plus(java.time.Duration.ofHours(1));
    }

    private static String duration(ICal.Component event, Instant start, boolean allDay) {
        Optional<String> explicit = event.value("DURATION");
        if (explicit.isPresent())
            return explicit.get();
        java.time.Duration length = java.time.Duration.between(start, end(event, start, allDay));
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

    /** The zone an all-day event's midnight is measured in, for tests and callers. */
    public static ZoneOffset allDayZone() {
        return ZoneOffset.UTC;
    }
}
