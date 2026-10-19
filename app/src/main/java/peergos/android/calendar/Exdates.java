package peergos.android.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

/**
 * The occurrences a series leaves out, or adds, in the two forms they take: EXDATE and RDATE
 * lines in a file, and the contract's EXDATE and RDATE columns, which share one format.
 *
 * Peergos has no exception rows. The web app changes one occurrence by excluding it from
 * the series and saving the new version as an event of its own, so an exclusion is the
 * whole of what a series has to say about its exceptions, and it has to reach the phone
 * for an occurrence moved or deleted in Peergos to stop showing in its old place.
 */
final class Exdates {

    private static final DateTimeFormatter UTC_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter LOCAL_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    private Exdates() {}

    /**
     * Every occurrence an event lists under this property, EXDATE or RDATE, across however
     * many lines it has, read the way the event's own times are read.
     */
    static List<Instant> of(ICal.Component event, String property, TimeZones zones, ZoneId floating) {
        List<Instant> out = new ArrayList<>();
        for (ICal.Property line : event.properties) {
            if (line.name.equals(property))
                out.addAll(values(line, zones, floating));
        }
        return out;
    }

    /** The instants one line names; a line may list several, comma separated, and an RDATE
     *  may give a period, of which the start is the occurrence. */
    static List<Instant> values(ICal.Property line, TimeZones zones, ZoneId floating) {
        List<Instant> out = new ArrayList<>();
        for (String value : line.value.split(",")) {
            String v = value.trim();
            int slash = v.indexOf('/');
            if (slash >= 0)
                v = v.substring(0, slash);
            if (v.isEmpty())
                continue;
            ICal.toInstant(new ICal.Property(line.name, line.params, v), zones, floating).ifPresent(out::add);
        }
        return out;
    }

    /**
     * The contract's EXDATE column, or null for none. Written in UTC, which needs no zone
     * prefix: the provider matches an exclusion against an occurrence by instant, so the
     * zone the file named only matters for reading it.
     */
    static String toColumn(List<Instant> instants) {
        if (instants.isEmpty())
            return null;
        return instants.stream().map(UTC_STAMP::format).collect(Collectors.joining(","));
    }

    /**
     * The contract's EXDATE column read back. A calendar app may write it in the provider's
     * own fuller form: lines separated by newlines, each optionally led by "zone;".
     */
    static List<Instant> fromColumn(String column) {
        List<Instant> out = new ArrayList<>();
        if (column == null)
            return out;
        for (String line : column.split("\n")) {
            String values = line.trim();
            ZoneId zone = ZoneOffset.UTC;
            int semicolon = values.indexOf(';');
            if (semicolon >= 0) {
                zone = zoneOf(values.substring(0, semicolon)).orElse(ZoneOffset.UTC);
                values = values.substring(semicolon + 1);
            }
            for (String value : values.split(",")) {
                parse(value.trim(), zone).ifPresent(out::add);
            }
        }
        return out;
    }

    private static Optional<Instant> parse(String value, ZoneId zone) {
        try {
            if (value.length() == 8)
                return Optional.of(LocalDate.parse(value, DATE).atStartOfDay(ZoneOffset.UTC).toInstant());
            if (value.endsWith("Z"))
                return Optional.of(LocalDateTime.parse(value.substring(0, value.length() - 1), LOCAL_STAMP)
                        .toInstant(ZoneOffset.UTC));
            return Optional.of(LocalDateTime.parse(value, LOCAL_STAMP).atZone(zone).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<ZoneId> zoneOf(String tzid) {
        try {
            return Optional.of(ZoneId.of(tzid));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
