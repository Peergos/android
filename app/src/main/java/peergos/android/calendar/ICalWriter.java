package peergos.android.calendar;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;

/**
 * Writes the few iCalendar properties the platform calendar can express.
 *
 * Edits are applied by patching the stored file rather than re-serialising the event from
 * the contract row. The row only carries what {@link EventTranslator} maps, so rebuilding
 * from it would quietly drop attendees, alarms, custom X- properties and timezone
 * definitions that the web app or a CalDAV client put there — a round trip through the
 * phone would strip them. Patching leaves everything it does not recognise alone.
 */
public final class ICalWriter {

    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneOffset.UTC);

    private ICalWriter() {}

    /** One content line, split so a patch can replace a property whatever its parameters. */
    public static final class Line {
        final String name;
        final String rest;

        Line(String name, String rest) {
            this.name = name;
            this.rest = rest;
        }

        @Override
        public String toString() {
            return name + rest;
        }
    }

    public static Line text(String name, String value) {
        return new Line(name, ":" + escape(value));
    }

    public static Line timestamp(String name, long millis) {
        return new Line(name, ":" + UTC_STAMP.format(Instant.ofEpochMilli(millis)));
    }

    public static Line date(String name, long millis) {
        return new Line(name, ";VALUE=DATE:" + DATE.format(Instant.ofEpochMilli(millis)));
    }

    public static Line raw(String name, String rest) {
        return new Line(name, ":" + rest);
    }

    /** The occurrences a series leaves out, in the form {@link #timestamp} or {@link #date}
     *  writes its start. */
    public static Line exdates(List<Instant> instants, boolean allDay) {
        StringBuilder values = new StringBuilder();
        for (Instant instant : instants) {
            if (values.length() > 0)
                values.append(',');
            values.append((allDay ? DATE : UTC_STAMP).format(instant));
        }
        return new Line("EXDATE", (allDay ? ";VALUE=DATE:" : ":") + values);
    }

    /**
     * Replaces these properties inside the first VEVENT, adding any that were absent and
     * dropping those named in {@code removals}. Everything else in the file survives
     * untouched — including the properties of an alarm nested in the event, which carries
     * a DESCRIPTION and a DURATION of its own that are not the event's.
     */
    public static String patch(String ics, List<Line> replacements, List<String> removals) {
        Map<String, Line> byName = new LinkedHashMap<>();
        for (Line line : replacements)
            byName.put(line.name, line);

        List<String> out = new ArrayList<>();
        boolean inEvent = false;
        boolean done = false;
        int nested = 0;
        for (String line : ICal.unfold(ics)) {
            String name = nameOf(line);
            if (! done && ! inEvent && name.equals("BEGIN") && valueOf(line).equalsIgnoreCase("VEVENT")) {
                inEvent = true;
                out.add(line);
                continue;
            }
            if (inEvent && nested == 0 && name.equals("END") && valueOf(line).equalsIgnoreCase("VEVENT")) {
                // Anything that was not already present is appended before the END, so the
                // order of the untouched properties is preserved.
                for (Line remaining : byName.values())
                    out.add(remaining.toString());
                byName.clear();
                out.add(line);
                inEvent = false;
                done = true;
                continue;
            }
            if (inEvent && (nested > 0 || name.equals("BEGIN"))) {
                if (name.equals("BEGIN"))
                    nested++;
                else if (name.equals("END"))
                    nested--;
                out.add(line);
                continue;
            }
            if (inEvent && removals.contains(name))
                continue;
            if (inEvent && byName.containsKey(name)) {
                out.add(byName.remove(name).toString());
                continue;
            }
            out.add(line);
        }
        return String.join("\r\n", out) + "\r\n";
    }

    /**
     * Puts the phone's own reminder into the file, as the single alarm the Reminders table
     * can hold: minutes before the start. An alarm this platform cannot express — absolute,
     * or measured from the end — is left exactly where it is, because nothing on the phone
     * was offered the chance to change it.
     */
    public static String withReminder(String ics, Optional<Integer> minutes, String description) {
        List<String> out = new ArrayList<>();
        List<String> alarm = null;
        boolean inEvent = false;
        boolean done = false;
        int nested = 0;
        for (String line : ICal.unfold(ics)) {
            String name = nameOf(line);
            String value = valueOf(line);
            if (alarm != null) {
                alarm.add(line);
                if (name.equals("BEGIN"))
                    nested++;
                if (name.equals("END") && --nested == 0) {
                    if (! ringsBeforeStart(alarm))
                        out.addAll(alarm);
                    alarm = null;
                }
                continue;
            }
            if (! done && ! inEvent && name.equals("BEGIN") && value.equalsIgnoreCase("VEVENT")) {
                inEvent = true;
                out.add(line);
                continue;
            }
            if (inEvent && nested == 0 && name.equals("BEGIN") && value.equalsIgnoreCase("VALARM")) {
                alarm = new ArrayList<>(List.of(line));
                nested = 1;
                continue;
            }
            if (inEvent && nested == 0 && name.equals("END") && value.equalsIgnoreCase("VEVENT")) {
                if (minutes.isPresent())
                    out.addAll(alarmBlock(minutes.get(), description));
                out.add(line);
                inEvent = false;
                done = true;
                continue;
            }
            if (inEvent && name.equals("BEGIN"))
                nested++;
            else if (inEvent && nested > 0 && name.equals("END"))
                nested--;
            out.add(line);
        }
        return String.join("\r\n", out) + "\r\n";
    }

    private static List<String> alarmBlock(int minutes, String description) {
        return List.of("BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT" + Math.max(0, minutes) + "M",
                text("DESCRIPTION", description == null || description.isEmpty() ? "Reminder" : description).toString(),
                "END:VALARM");
    }

    /** Whether this alarm block is the shape the phone shows, and so ours to rewrite. */
    private static boolean ringsBeforeStart(List<String> alarmLines) {
        for (String line : alarmLines) {
            if (! nameOf(line).equals("TRIGGER"))
                continue;
            return isMinutesBeforeStart(valueOf(line), paramOf(line, "VALUE"), paramOf(line, "RELATED"));
        }
        return false;
    }

    /**
     * A trigger the Reminders table can hold: a duration measured back from the start.
     * Both directions of the mirror ask this one question — see EventTranslator.
     */
    static boolean isMinutesBeforeStart(String value, String valueParam, String relatedParam) {
        if (valueParam != null && ! valueParam.equalsIgnoreCase("DURATION"))
            return false;
        if (relatedParam != null && relatedParam.equalsIgnoreCase("END"))
            return false;
        return value.startsWith("-");
    }

    /** The value of one parameter on a content line, or null if it carries none. */
    private static String paramOf(String line, String parameter) {
        int colon = line.indexOf(':');
        int start = line.indexOf(';');
        if (start < 0 || (colon >= 0 && start > colon))
            return null;
        String params = line.substring(start + 1, colon < 0 ? line.length() : colon);
        for (String each : params.split(";")) {
            int equals = each.indexOf('=');
            if (equals > 0 && each.substring(0, equals).trim().equalsIgnoreCase(parameter))
                return each.substring(equals + 1).trim();
        }
        return null;
    }

    /**
     * Takes one occurrence out of a series, as the web app does when that occurrence alone
     * is changed or deleted: an EXDATE on the series, in the form of its DTSTART. An
     * override a CalDAV client stored for the same occurrence — a VEVENT carrying its
     * RECURRENCE-ID — goes too, or it would go on drawing the occurrence the phone just
     * removed. The file comes back unchanged if there is no series in it, or it already
     * leaves that occurrence out.
     */
    public static String excludeOccurrence(String ics, long instanceMillis) {
        Instant instance = Instant.ofEpochMilli(instanceMillis);
        List<String> lines = ICal.unfold(ics);
        // top-level VEVENT blocks, as [first line, END line]
        List<int[]> events = new ArrayList<>();
        int depth = 0;
        int open = -1;
        for (int i = 0; i < lines.size(); i++) {
            String name = nameOf(lines.get(i));
            if (name.equals("BEGIN")) {
                depth++;
                if (depth == 2 && valueOf(lines.get(i)).equalsIgnoreCase("VEVENT"))
                    open = i;
            } else if (name.equals("END")) {
                if (depth == 2 && open >= 0) {
                    events.add(new int[]{open, i});
                    open = -1;
                }
                depth--;
            }
        }
        int[] series = null;
        ICal.Property start = null;
        boolean excluded = false;
        List<int[]> overrides = new ArrayList<>();
        for (int[] event : events) {
            Optional<ICal.Property> recurrenceId = ownProperty(lines, event, "RECURRENCE-ID");
            if (recurrenceId.isPresent()) {
                if (ICal.toInstant(recurrenceId.get()).map(instance::equals).orElse(false))
                    overrides.add(event);
                continue;
            }
            if (series != null)
                continue;
            Optional<ICal.Property> dtstart = ownProperty(lines, event, "DTSTART");
            if (dtstart.isEmpty() || ownProperty(lines, event, "RRULE").isEmpty())
                continue;
            series = event;
            start = dtstart.get();
            for (ICal.Property exdate : ownProperties(lines, event, "EXDATE"))
                excluded |= Exdates.values(exdate).contains(instance);
        }
        if (series == null || (excluded && overrides.isEmpty()))
            return ics;

        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int index = i;
            if (overrides.stream().anyMatch(o -> index >= o[0] && index <= o[1]))
                continue;
            if (i == series[1] && ! excluded)
                out.add(Exdates.line(start, List.of(instance)));
            out.add(lines.get(i));
        }
        return String.join("\r\n", out) + "\r\n";
    }

    /** A property of the event itself, not of an alarm or other component nested in it. */
    private static Optional<ICal.Property> ownProperty(List<String> lines, int[] event, String name) {
        List<ICal.Property> all = ownProperties(lines, event, name);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    private static List<ICal.Property> ownProperties(List<String> lines, int[] event, String name) {
        List<ICal.Property> out = new ArrayList<>();
        int nested = 0;
        for (int i = event[0] + 1; i < event[1]; i++) {
            String line = lines.get(i);
            String lineName = nameOf(line);
            if (lineName.equals("BEGIN"))
                nested++;
            else if (lineName.equals("END"))
                nested--;
            else if (nested == 0 && lineName.equals(name))
                ICal.parseProperty(line).ifPresent(out::add);
        }
        return out;
    }

    /** A whole VCALENDAR for an event that does not exist in Peergos yet. */
    public static String create(String uid, List<Line> properties) {
        List<String> out = new ArrayList<>();
        out.add("BEGIN:VCALENDAR");
        out.add("VERSION:2.0");
        out.add("PRODID:-//Peergos//Android//EN");
        out.add("BEGIN:VEVENT");
        out.add(text("UID", uid).toString());
        out.add(timestamp("DTSTAMP", System.currentTimeMillis()).toString());
        for (Line line : properties)
            out.add(line.toString());
        out.add("END:VEVENT");
        out.add("END:VCALENDAR");
        return String.join("\r\n", out) + "\r\n";
    }

    private static String nameOf(String line) {
        int end = line.length();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ':' || c == ';') {
                end = i;
                break;
            }
        }
        return line.substring(0, end).trim().toUpperCase(Locale.ROOT);
    }

    private static String valueOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    /** RFC 5545 escaping for TEXT values. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case ';': out.append("\\;"); break;
                case ',': out.append("\\,"); break;
                case '\n': out.append("\\n"); break;
                case '\r': break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
