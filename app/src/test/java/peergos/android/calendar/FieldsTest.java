package peergos.android.calendar;

import android.provider.CalendarContract;

import org.junit.Assert;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

/**
 * How the phone's side of the mirror writes what it now carries beyond title and time: the
 * form a start is written in, the zones it names, attendees and colours.
 */
public class FieldsTest {

    private static final String OUTLOOK_ZONE = "(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna";

    private static ICal.Property property(String line) {
        return ICal.parseProperty(line).orElseThrow();
    }

    @Test
    public void aStartIsWrittenInTheFormItCameIn() {
        Instant nine = Instant.parse("2026-07-15T07:00:00Z");
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        Assert.assertEquals("DTSTART:20260715T070000Z",
                ICalWriter.Form.of(property("DTSTART:20260101T000000Z"), TimeZones.none(), tokyo).line("DTSTART", nine).toString());
        Assert.assertEquals("DTSTART;TZID=W. Europe Standard Time:20260715T090000",
                ICalWriter.Form.of(property("DTSTART;TZID=W. Europe Standard Time:20260101T000000"), TimeZones.none(), tokyo)
                        .line("DTSTART", nine).toString());
        // floating is read and written in the phone's zone
        Assert.assertEquals("DTSTART:20260715T160000",
                ICalWriter.Form.of(property("DTSTART:20260101T000000"), TimeZones.none(), tokyo).line("DTSTART", nine).toString());
        Assert.assertEquals("EXDATE;VALUE=DATE:20260715",
                ICalWriter.Form.of(property("DTSTART;VALUE=DATE:20260101"), TimeZones.none(), tokyo)
                        .list("EXDATE", List.of(Instant.parse("2026-07-15T00:00:00Z"))).toString());
    }

    @Test
    public void aZoneNameThatWouldEndTheParameterIsQuoted() {
        ICalWriter.Form form = ICalWriter.Form.zoned(OUTLOOK_ZONE, ZoneId.of("Europe/Amsterdam"));
        String line = form.list("EXDATE", List.of(Instant.parse("2026-01-12T08:00:00Z"))).toString();
        Assert.assertEquals("EXDATE;TZID=\"" + OUTLOOK_ZONE + "\":20260112T090000", line);
        Assert.assertEquals(Optional.of(OUTLOOK_ZONE), property(line).param("TZID"));
    }

    /** What the phone writes for a zone, read back by the same code the server and phone read with. */
    @Test
    public void aGeneratedTimeZoneReadsBackAsItsZone() {
        ZoneId newYork = ZoneId.of("America/New_York");
        List<String> block = ICalWriter.vtimezone(newYork, Instant.parse("2026-06-01T00:00:00Z"));
        Assert.assertTrue(block.toString(), String.join("\n", block).contains(
                "BEGIN:DAYLIGHT\nDTSTART:20260308T020000\nTZOFFSETFROM:-0500\nTZOFFSETTO:-0400\nEND:DAYLIGHT"));
        String renamed = "BEGIN:VCALENDAR\r\n" + String.join("\r\n", block).replace("TZID:America/New_York", "TZID:Mine")
                + "\r\nEND:VCALENDAR\r\n";
        TimeZones zones = TimeZones.of(ICal.parse(renamed).orElseThrow());
        for (String local : List.of("2026-01-15T09:00", "2026-03-09T09:00", "2026-07-15T09:00", "2026-11-02T09:00")) {
            LocalDateTime time = LocalDateTime.parse(local);
            Assert.assertEquals(local, Optional.of(time.atZone(newYork).toInstant()), zones.instant("Mine", time));
        }
    }

    @Test
    public void aZoneIsDefinedOnce() {
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:a\r\n"
                + "DTSTART;TZID=Europe/London:20260715T090000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        String once = ICalWriter.withTimeZone(ics, ZoneId.of("Europe/London"));
        Assert.assertTrue(once, once.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTIMEZONE\r\nTZID:Europe/London\r\n"));
        Assert.assertEquals(once, ICalWriter.withTimeZone(once, ZoneId.of("Europe/London")));
    }

    @Test
    public void aRepeatedPropertyIsReplacedAsAWhole() {
        String ics = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nATTENDEE:mailto:a@x\r\nSUMMARY:S\r\n"
                + "ATTENDEE:mailto:b@x\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        String out = ICalWriter.patch(ics, List.of(new ICalWriter.Line("ATTENDEE", ":mailto:c@x"),
                new ICalWriter.Line("ATTENDEE", ":mailto:d@x")), List.of());
        Assert.assertEquals("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nATTENDEE:mailto:c@x\r\nATTENDEE:mailto:d@x\r\n"
                + "SUMMARY:S\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n", out);
    }

    /** A CalDAV client may put an override before its series; an edit to the series lands on the series. */
    @Test
    public void aPatchGoesToTheSeriesNotAnOverrideBeforeIt() {
        String ics = "BEGIN:VCALENDAR\r\n"
                + "BEGIN:VEVENT\r\nUID:w\r\nRECURRENCE-ID:20260112T090000Z\r\nSUMMARY:Moved\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:w\r\nSUMMARY:Weekly\r\nRRULE:FREQ=WEEKLY\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        String out = ICalWriter.patch(ics, List.of(ICalWriter.text("SUMMARY", "Renamed")), List.of());
        Assert.assertTrue(out, out.contains("SUMMARY:Moved"));
        Assert.assertTrue(out, out.contains("SUMMARY:Renamed\r\nRRULE"));
        String reminded = ICalWriter.withReminder(ics, Optional.of(10), "Weekly");
        Assert.assertTrue(reminded, reminded.contains("RRULE:FREQ=WEEKLY\r\nBEGIN:VALARM"));
    }

    @Test
    public void anAttendeeKeepsWhatThePhoneDidNotChange() {
        ICal.Component event = ICal.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\n"
                + "ATTENDEE;CN=Bob;RSVP=TRUE;ROLE=CHAIR;PARTSTAT=NEEDS-ACTION:MAILTO:Bob@Example.com\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n").orElseThrow().scheduleComponents().get(0);
        EventTranslator.Attendee accepted = new EventTranslator.Attendee("bob@example.com", "Bob",
                CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.TYPE_REQUIRED,
                CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED);
        Assert.assertEquals("ATTENDEE;CN=Bob;RSVP=TRUE;ROLE=CHAIR;PARTSTAT=ACCEPTED:MAILTO:Bob@Example.com",
                CalendarUploader.attendeeLine(accepted, Optional.of(event)).toString());
        EventTranslator.Attendee room = new EventTranslator.Attendee("room@example.com", "Room 1, East",
                CalendarContract.Attendees.RELATIONSHIP_ATTENDEE, CalendarContract.Attendees.TYPE_RESOURCE,
                CalendarContract.Attendees.ATTENDEE_STATUS_NONE);
        Assert.assertEquals("ATTENDEE;CN=\"Room 1, East\";CUTYPE=RESOURCE;PARTSTAT=NEEDS-ACTION:mailto:room@example.com",
                CalendarUploader.attendeeLine(room, Optional.of(event)).toString());
    }

    @Test
    public void coloursByName() {
        Assert.assertEquals(Optional.of(0xff1e90ff), Colours.parse("DodgerBlue"));
        Assert.assertEquals(Optional.of(0xff123456), Colours.parse("#123456"));
        Assert.assertEquals(Optional.empty(), Colours.parse("not a colour"));
        Assert.assertEquals("navy", Colours.nameOf(0xff000080));
        // a colour no name has goes back as the nearest one
        Assert.assertEquals("navy", Colours.nameOf(0xff000081));
        Assert.assertEquals(Optional.of("tomato"), Colours.paletteKey("Tomato"));
        Assert.assertEquals(Optional.empty(), Colours.paletteKey("navy"));
        for (String key : Colours.PALETTE)
            Assert.assertTrue(key, Colours.parse(key).isPresent());
    }
}
