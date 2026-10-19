package peergos.android.calendar;

import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

/**
 * One occurrence of a series taken out from the phone, written the way the web app writes
 * it: an EXDATE in the form of the series' own start, so the web app reads it back through
 * the same zone and draws the series without that occurrence.
 */
public class ExcludeOccurrenceTest {

    private static String series(String start, String extra) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
                + "UID:weekly\r\nDTSTAMP:20260101T090000Z\r\n" + start + "\r\n"
                + "DTEND:20260105T100000Z\r\nSUMMARY:Weekly\r\nRRULE:FREQ=WEEKLY\r\n" + extra
                + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Weekly\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private static long at(String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    @Test
    public void aUtcSeriesGetsAUtcExdate() {
        String out = ICalWriter.excludeOccurrence(series("DTSTART:20260105T090000Z", ""), at("2026-01-12T09:00:00Z"), ZoneOffset.UTC);
        Assert.assertTrue(out, out.contains("EXDATE:20260112T090000Z\r\nEND:VEVENT"));
        // the alarm is untouched and the EXDATE lands on the event, not inside it
        Assert.assertTrue(out, out.contains("TRIGGER:-PT15M\r\nEND:VALARM\r\nEXDATE"));
    }

    @Test
    public void aZonedSeriesGetsAnExdateInItsOwnZone() {
        String out = ICalWriter.excludeOccurrence(series("DTSTART;TZID=Europe/London:20260705T090000", ""),
                at("2026-07-12T08:00:00Z"), ZoneOffset.UTC);
        Assert.assertTrue(out, out.contains("EXDATE;TZID=Europe/London:20260712T090000"));
    }

    @Test
    public void anAllDaySeriesGetsADateExdate() {
        String out = ICalWriter.excludeOccurrence(series("DTSTART;VALUE=DATE:20260105", ""), at("2026-01-12T00:00:00Z"), ZoneOffset.UTC);
        Assert.assertTrue(out, out.contains("EXDATE;VALUE=DATE:20260112"));
    }

    @Test
    public void anOccurrenceAlreadyExcludedLeavesTheFileAlone() {
        String in = series("DTSTART:20260105T090000Z", "EXDATE:20260119T090000Z,20260112T090000Z\r\n");
        Assert.assertEquals(in, ICalWriter.excludeOccurrence(in, at("2026-01-12T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    public void aFileWithNoSeriesIsLeftAlone() {
        String single = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:one\r\n"
                + "DTSTART:20260105T090000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        Assert.assertEquals(single, ICalWriter.excludeOccurrence(single, at("2026-01-05T09:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    public void anOverrideForTheSameOccurrenceGoes() {
        String withOverrides = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"
                + "BEGIN:VEVENT\r\nUID:weekly\r\nDTSTART:20260105T090000Z\r\nDTEND:20260105T100000Z\r\n"
                + "RRULE:FREQ=WEEKLY\r\nSUMMARY:Weekly\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:weekly\r\nRECURRENCE-ID:20260112T090000Z\r\n"
                + "DTSTART:20260112T140000Z\r\nDTEND:20260112T150000Z\r\nSUMMARY:Moved\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:weekly\r\nRECURRENCE-ID:20260119T090000Z\r\n"
                + "DTSTART:20260119T140000Z\r\nDTEND:20260119T150000Z\r\nSUMMARY:Kept\r\nEND:VEVENT\r\n"
                + "END:VCALENDAR\r\n";
        String out = ICalWriter.excludeOccurrence(withOverrides, at("2026-01-12T09:00:00Z"), ZoneOffset.UTC);
        Assert.assertFalse(out, out.contains("SUMMARY:Moved"));
        Assert.assertTrue(out, out.contains("SUMMARY:Kept"));
        Assert.assertTrue(out, out.contains("RRULE:FREQ=WEEKLY\r\nSUMMARY:Weekly\r\nEXDATE:20260112T090000Z\r\nEND:VEVENT"));
        Assert.assertEquals(1, out.split("RECURRENCE-ID").length - 1);
    }

    @Test
    public void exdatesAcrossLinesAndListsAreAllRead() {
        ICal.Component event = ICal.parse(series("DTSTART;TZID=Europe/London:20260105T090000",
                "EXDATE;TZID=Europe/London:20260112T090000,20260119T090000\r\nEXDATE:20260126T090000Z\r\n"))
                .orElseThrow().scheduleComponents().get(0);
        Assert.assertEquals(List.of(Instant.parse("2026-01-12T09:00:00Z"), Instant.parse("2026-01-19T09:00:00Z"),
                Instant.parse("2026-01-26T09:00:00Z")), Exdates.of(event, "EXDATE", TimeZones.none(), ZoneOffset.UTC));
    }

    @Test
    public void theColumnRoundTrips() {
        List<Instant> instants = List.of(Instant.parse("2026-01-12T09:00:00Z"), Instant.parse("2026-01-19T09:00:00Z"));
        Assert.assertEquals("20260112T090000Z,20260119T090000Z", Exdates.toColumn(instants));
        Assert.assertEquals(instants, Exdates.fromColumn(Exdates.toColumn(instants)));
        Assert.assertNull(Exdates.toColumn(List.of()));
    }

    @Test
    public void theProvidersFullerColumnFormIsRead() {
        // zone prefix, several lines, and an all-day date
        Assert.assertEquals(List.of(Instant.parse("2026-07-12T08:00:00Z"), Instant.parse("2026-01-19T09:00:00Z"),
                        Instant.parse("2026-01-26T00:00:00Z")),
                Exdates.fromColumn("Europe/London;20260712T090000\n20260119T090000Z\n20260126"));
    }

    @Test
    public void anUploadedSeriesWritesItsExdatesLikeItsStart() {
        List<Instant> instants = List.of(Instant.parse("2026-01-12T09:00:00Z"), Instant.parse("2026-01-19T09:00:00Z"));
        Assert.assertEquals("EXDATE:20260112T090000Z,20260119T090000Z", ICalWriter.Form.utc().list("EXDATE", instants).toString());
        Assert.assertEquals("EXDATE;VALUE=DATE:20260112,20260119", ICalWriter.Form.date().list("EXDATE", instants).toString());
    }

    @Test
    public void patchingExdatesReplacesEveryLine() {
        String in = series("DTSTART:20260105T090000Z", "EXDATE:20260112T090000Z\r\nEXDATE:20260119T090000Z\r\n");
        String out = ICalWriter.patch(in,
                List.of(ICalWriter.Form.utc().list("EXDATE", List.of(Instant.parse("2026-01-26T09:00:00Z")))),
                List.of("EXDATE"));
        Assert.assertEquals(1, out.split("EXDATE").length - 1);
        Assert.assertTrue(out, out.contains("EXDATE:20260126T090000Z"));
    }
}
