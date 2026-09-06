package peergos.android.calendar;

import android.content.ContentValues;
import android.provider.CalendarContract;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;

/**
 * iCalendar to CalendarContract. The parser is the one the CalDAV bridge uses, compiled
 * into the app from the server jar, so the two surfaces cannot drift in how they read a
 * date or decide that an event recurs.
 *
 * Only the fields the web calendar app actually writes are mapped. Attendees and exceptions
 * to recurring events live in their own contract tables and are left for the write path,
 * which is where they start to matter. Alarms are the exception: a reminder is the whole
 * point of mirroring for many people, so a VALARM becomes a Reminders row, which is why a
 * translation carries {@link Translation#reminderMinutes} beside the event row.
 */
public final class EventTranslator {

    private EventTranslator() {}

    /** An event row, and the one alarm the Reminders table can hold for it. */
    public record Translation(ContentValues values, Optional<Integer> reminderMinutes) {}

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
        ICal.Component event = parts.get(0);
        // A calendar collection also holds tasks, which the contract has no table for, and
        // a VTODO with a DTSTART would otherwise map cleanly onto an event row and appear
        // on the phone as one.
        if (! event.name.equals("VEVENT"))
            return Optional.empty();
        Optional<ICal.Property> start = event.property("DTSTART");
        if (start.isEmpty())
            return Optional.empty();
        Optional<Instant> from = ICal.toInstant(start.get());
        if (from.isEmpty())
            return Optional.empty();

        Optional<Integer> reminder = reminderMinutes(event);
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, event.value("SUMMARY").orElse(""));
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

        values.put(CalendarContract.Events.HAS_ALARM, reminder.isPresent() ? 1 : 0);

        Optional<String> rrule = event.value("RRULE");
        if (rrule.isPresent()) {
            // The contract requires a duration rather than an end for a recurring event,
            // and rejects the row outright if both are set.
            values.put(CalendarContract.Events.RRULE, rrule.get());
            values.put(CalendarContract.Events.DURATION, duration(event, from.get(), allDay));
        } else {
            values.put(CalendarContract.Events.DTEND, end(event, from.get(), allDay).toEpochMilli());
        }
        return Optional.of(new Translation(values, reminder));
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
