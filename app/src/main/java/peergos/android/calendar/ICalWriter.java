package peergos.android.calendar;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

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
    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US);

    private ICalWriter() {}

    /** One content line, split so a patch can replace a property whatever its parameters. */
    public static final class Line {
        final String name;
        final String rest;

        public Line(String name, String rest) {
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
     * How an event's times are written: as dates, in UTC, as local times in a named zone, or
     * floating. RFC 5545 has an EXDATE or RDATE take the form of the DTSTART it goes with,
     * and the web app reads one back through that start's zone, so all three share a form.
     */
    public static final class Form {
        final boolean date;
        /** The TZID written, which may be a name only the file's own VTIMEZONE defines. */
        final Optional<String> tzid;
        /** Where local times are read, for a zoned or floating form. */
        final ZoneId zone;

        private Form(boolean date, Optional<String> tzid, ZoneId zone) {
            this.date = date;
            this.tzid = tzid;
            this.zone = zone;
        }

        public static Form date() {
            return new Form(true, Optional.empty(), ZoneOffset.UTC);
        }

        public static Form utc() {
            return new Form(false, Optional.empty(), ZoneOffset.UTC);
        }

        public static Form zoned(String tzid, ZoneId zone) {
            return new Form(false, Optional.of(tzid), zone);
        }

        public static Form floating(ZoneId zone) {
            return new Form(false, Optional.empty(), zone);
        }

        /** The form a file's DTSTART is in, read the way the translator reads it. */
        public static Form of(ICal.Property dtstart, TimeZones zones, ZoneId floating) {
            String value = dtstart.value.trim();
            if (value.length() == 8 || dtstart.param("VALUE").map(v -> v.equalsIgnoreCase("DATE")).orElse(false))
                return date();
            if (value.endsWith("Z"))
                return utc();
            Optional<String> tzid = dtstart.param("TZID");
            if (tzid.isPresent())
                return zoned(tzid.get(), zones.zone(tzid.get()).orElse(floating));
            return floating(floating);
        }

        boolean isUtc() {
            return ! date && tzid.isEmpty() && zone.equals(ZoneOffset.UTC);
        }

        private String params() {
            if (date)
                return ";VALUE=DATE";
            return tzid.map(z -> ";TZID=" + paramValue(z)).orElse("");
        }

        private String format(Instant instant) {
            if (date)
                return DATE.format(instant);
            if (isUtc())
                return UTC_STAMP.format(instant);
            return LOCAL.format(LocalDateTime.ofInstant(instant, zone));
        }

        public Line line(String name, Instant instant) {
            return new Line(name, params() + ":" + format(instant));
        }

        /** One line listing several instants, as EXDATE and RDATE do. */
        public Line list(String name, List<Instant> instants) {
            return new Line(name, params() + ":" + instants.stream().map(this::format).collect(Collectors.joining(",")));
        }
    }

    /** A parameter value, quoted if it holds a character that would otherwise end it. */
    static String paramValue(String value) {
        return value.indexOf(':') >= 0 || value.indexOf(';') >= 0 || value.indexOf(',') >= 0
                ? "\"" + value.replace("\"", "") + "\"" : value;
    }

    /**
     * Replaces these properties inside the series' VEVENT, adding any that were absent and
     * dropping those named in {@code removals}. Everything else in the file survives
     * untouched — including the properties of an alarm nested in the event, which carries
     * a DESCRIPTION and a DURATION of its own that are not the event's. A property given
     * several times, as ATTENDEE is, replaces every line of that name with all of them.
     */
    public static String patch(String ics, List<Line> replacements, List<String> removals) {
        Map<String, List<Line>> byName = new LinkedHashMap<>();
        for (Line line : replacements)
            byName.computeIfAbsent(line.name, n -> new ArrayList<>()).add(line);

        List<String> out = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        boolean inEvent = false;
        boolean done = false;
        int nested = 0;
        List<String> lines = ICal.unfold(ics);
        int target = seriesStart(lines);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String name = nameOf(line);
            if (! done && ! inEvent && i == target) {
                inEvent = true;
                out.add(line);
                continue;
            }
            if (inEvent && nested == 0 && name.equals("END") && valueOf(line).equalsIgnoreCase("VEVENT")) {
                // Anything that was not already present is appended before the END, so the
                // order of the untouched properties is preserved.
                for (List<Line> remaining : byName.values())
                    for (Line each : remaining)
                        out.add(each.toString());
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
                for (Line each : byName.remove(name))
                    out.add(each.toString());
                replaced.add(name);
                continue;
            }
            // the second and later lines of a property already written out above
            if (inEvent && replaced.contains(name))
                continue;
            out.add(line);
        }
        return String.join("\r\n", out) + "\r\n";
    }

    /**
     * Adds a VTIMEZONE for a zone the file names but does not define, which RFC 5545 asks
     * of every TZID. It covers a year back to five ahead, as the web app's does: a client
     * that knows the zone by name has no need of it, and the others only show what is near.
     */
    public static String withTimeZone(String ics, ZoneId zone) {
        String tzid = zone.getId();
        List<String> lines = ICal.unfold(ics);
        boolean defined = ICal.parse(ics).map(c -> c.children("VTIMEZONE").stream()
                .anyMatch(z -> z.value("TZID").map(tzid::equals).orElse(false))).orElse(true);
        if (defined)
            return ics;
        List<String> out = new ArrayList<>();
        boolean added = false;
        for (String line : lines) {
            if (! added && nameOf(line).equals("BEGIN") && ! valueOf(line).equalsIgnoreCase("VCALENDAR")) {
                out.addAll(vtimezone(zone, Instant.now()));
                added = true;
            }
            out.add(line);
        }
        return String.join("\r\n", out) + "\r\n";
    }

    /** A VTIMEZONE for an IANA zone: each change of offset from a year before {@code now} to five after. */
    static List<String> vtimezone(ZoneId zone, Instant now) {
        ZoneRules rules = zone.getRules();
        int year = LocalDateTime.ofInstant(now, ZoneOffset.UTC).getYear();
        Instant from = LocalDateTime.of(year - 1, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
        Instant to = LocalDateTime.of(year + 5, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);
        List<String> out = new ArrayList<>(List.of("BEGIN:VTIMEZONE", "TZID:" + zone.getId()));
        boolean any = false;
        for (ZoneOffsetTransition t = rules.nextTransition(from); t != null && t.getInstant().isBefore(to);
             t = rules.nextTransition(t.getInstant())) {
            String kind = t.getOffsetAfter().getTotalSeconds() > t.getOffsetBefore().getTotalSeconds() ? "DAYLIGHT" : "STANDARD";
            // An observance starts at the local time just before it, in the offset it changes from.
            out.addAll(List.of("BEGIN:" + kind, "DTSTART:" + LOCAL.format(t.getDateTimeBefore()),
                    "TZOFFSETFROM:" + offset(t.getOffsetBefore()), "TZOFFSETTO:" + offset(t.getOffsetAfter()),
                    "END:" + kind));
            any = true;
        }
        if (! any) {
            String fixed = offset(rules.getOffset(from));
            out.addAll(List.of("BEGIN:STANDARD", "DTSTART:" + LOCAL.format(LocalDateTime.of(year - 1, 1, 1, 0, 0)),
                    "TZOFFSETFROM:" + fixed, "TZOFFSETTO:" + fixed, "END:STANDARD"));
        }
        out.add("END:VTIMEZONE");
        return out;
    }

    private static String offset(ZoneOffset offset) {
        int total = offset.getTotalSeconds();
        int abs = Math.abs(total);
        String hhmm = String.format(Locale.US, "%02d%02d", abs / 3600, (abs % 3600) / 60);
        return (total < 0 ? "-" : "+") + hhmm + (abs % 60 == 0 ? "" : String.format(Locale.US, "%02d", abs % 60));
    }

    /**
     * Puts the phone's own reminder into the file, as the single alarm the Reminders table
     * can hold: minutes before the start. An alarm this platform cannot express — absolute,
     * or measured from the end — is left exactly where it is, because nothing on the phone
     * was offered the chance to change it.
     */
    public static String withReminders(String ics, List<Integer> minutes, String description) {
        List<String> out = new ArrayList<>();
        List<String> alarm = null;
        boolean inEvent = false;
        boolean done = false;
        int nested = 0;
        List<String> lines = ICal.unfold(ics);
        int target = seriesStart(lines);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
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
            if (! done && ! inEvent && i == target) {
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
                for (int each : minutes)
                    out.addAll(alarmBlock(each, description));
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
     * leaves that occurrence out. {@code floating} is where a floating time in it is read,
     * the phone's own zone.
     */
    public static String excludeOccurrence(String ics, long instanceMillis, ZoneId floating) {
        Instant instance = Instant.ofEpochMilli(instanceMillis);
        List<String> lines = ICal.unfold(ics);
        TimeZones zones = ICal.parse(ics).map(TimeZones::of).orElse(TimeZones.none());
        List<int[]> events = topLevelEvents(lines);
        int[] series = null;
        ICal.Property start = null;
        boolean excluded = false;
        List<int[]> overrides = new ArrayList<>();
        for (int[] event : events) {
            Optional<ICal.Property> recurrenceId = ownProperty(lines, event, "RECURRENCE-ID");
            if (recurrenceId.isPresent()) {
                if (ICal.toInstant(recurrenceId.get(), zones, floating).map(instance::equals).orElse(false))
                    overrides.add(event);
                continue;
            }
            if (series != null)
                continue;
            Optional<ICal.Property> dtstart = ownProperty(lines, event, "DTSTART");
            if (dtstart.isEmpty() || (ownProperty(lines, event, "RRULE").isEmpty()
                    && ownProperty(lines, event, "RDATE").isEmpty()))
                continue;
            series = event;
            start = dtstart.get();
            for (ICal.Property exdate : ownProperties(lines, event, "EXDATE"))
                excluded |= Exdates.values(exdate, zones, floating).contains(instance);
        }
        if (series == null || (excluded && overrides.isEmpty()))
            return ics;

        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int index = i;
            if (overrides.stream().anyMatch(o -> index >= o[0] && index <= o[1]))
                continue;
            if (i == series[1] && ! excluded)
                out.add(Form.of(start, zones, floating).list("EXDATE", List.of(instance)).toString());
            out.add(lines.get(i));
        }
        return String.join("\r\n", out) + "\r\n";
    }

    /**
     * The line the event the phone shows as the series begins on: the first VEVENT that
     * overrides no occurrence of another, which a CalDAV client may write after its overrides.
     */
    private static int seriesStart(List<String> lines) {
        List<int[]> events = topLevelEvents(lines);
        for (int[] event : events)
            if (ownProperty(lines, event, "RECURRENCE-ID").isEmpty())
                return event[0];
        return events.isEmpty() ? -1 : events.get(0)[0];
    }

    /** Each VEVENT directly inside the VCALENDAR, as [its BEGIN line, its END line]. */
    private static List<int[]> topLevelEvents(List<String> lines) {
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
        return events;
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
        if (properties.stream().noneMatch(line -> line.name.equals("DTSTAMP")))
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
