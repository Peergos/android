package peergos.android.calendar;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
     * Replaces these properties inside the first VEVENT, adding any that were absent and
     * dropping those given a null value. Everything else in the file survives untouched.
     */
    public static String patch(String ics, List<Line> replacements, List<String> removals) {
        Map<String, Line> byName = new LinkedHashMap<>();
        for (Line line : replacements)
            byName.put(line.name, line);

        List<String> out = new ArrayList<>();
        boolean inEvent = false;
        boolean done = false;
        for (String line : ICal.unfold(ics)) {
            String name = nameOf(line);
            if (! done && name.equals("BEGIN") && valueOf(line).equalsIgnoreCase("VEVENT")) {
                inEvent = true;
                out.add(line);
                continue;
            }
            if (inEvent && name.equals("END") && valueOf(line).equalsIgnoreCase("VEVENT")) {
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
