package peergos.android.calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ICalWriterTest {

    private static final String STORED =
            "BEGIN:VCALENDAR\r\n"
            + "VERSION:2.0\r\n"
            + "PRODID:-//Peergos//web//EN\r\n"
            + "BEGIN:VEVENT\r\n"
            + "UID:existing-event\r\n"
            + "SUMMARY:Old title\r\n"
            + "DTSTART:20240315T090000Z\r\n"
            + "DTEND:20240315T100000Z\r\n"
            + "ATTENDEE;CN=Someone:mailto:someone@example.com\r\n"
            + "X-PEERGOS-CUSTOM:keep me\r\n"
            + "BEGIN:VALARM\r\n"
            + "TRIGGER:-PT15M\r\n"
            + "ACTION:DISPLAY\r\n"
            + "END:VALARM\r\n"
            + "END:VEVENT\r\n"
            + "END:VCALENDAR\r\n";

    /** The whole point of patching rather than re-serialising. */
    @Test
    public void patchKeepsWhatItDoesNotUnderstand() {
        String patched = ICalWriter.patch(STORED,
                List.of(ICalWriter.text("SUMMARY", "New title"),
                        ICalWriter.timestamp("DTSTART", 1710493200000L)),
                Collections.emptyList());

        assertTrue("summary replaced", patched.contains("SUMMARY:New title"));
        assertFalse("old summary gone", patched.contains("SUMMARY:Old title"));
        assertTrue("start replaced", patched.contains("DTSTART:20240315T090000Z"));

        assertTrue("attendee kept", patched.contains("ATTENDEE;CN=Someone:mailto:someone@example.com"));
        assertTrue("custom property kept", patched.contains("X-PEERGOS-CUSTOM:keep me"));
        assertTrue("alarm kept", patched.contains("TRIGGER:-PT15M"));
        assertTrue("uid kept", patched.contains("UID:existing-event"));
    }

    @Test
    public void patchAddsAPropertyThatWasAbsent() {
        String patched = ICalWriter.patch(STORED,
                List.of(ICalWriter.text("LOCATION", "Room 2")), Collections.emptyList());
        assertTrue(patched.contains("LOCATION:Room 2"));
        // added inside the event, not after it
        assertTrue(patched.indexOf("LOCATION:Room 2") < patched.indexOf("END:VEVENT"));
    }

    @Test
    public void patchCanRemoveAProperty() {
        String patched = ICalWriter.patch(STORED, Collections.emptyList(), List.of("ATTENDEE"));
        assertFalse(patched.contains("ATTENDEE"));
        assertTrue(patched.contains("UID:existing-event"));
    }

    /** A VALARM has its own END, which must not be mistaken for the event's. */
    @Test
    public void patchDoesNotStopAtANestedComponent() {
        String patched = ICalWriter.patch(STORED,
                List.of(ICalWriter.text("LOCATION", "Room 2")), Collections.emptyList());
        assertEquals("one END:VEVENT", 1, countOf(patched, "END:VEVENT"));
        assertEquals("alarm still closed", 1, countOf(patched, "END:VALARM"));
    }

    @Test
    public void createEmitsWhatTheWebAppRequires() {
        String ics = ICalWriter.create("new-uid",
                List.of(ICalWriter.text("SUMMARY", "Coffee"),
                        ICalWriter.timestamp("DTSTART", 1710493200000L),
                        ICalWriter.timestamp("DTEND", 1710496800000L)));
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"));
        assertTrue(ics.contains("UID:new-uid"));
        assertTrue(ics.contains("SUMMARY:Coffee"));
        assertTrue(ics.contains("DTSTART:20240315T090000Z"));
        assertTrue("the web app treats an event with no DTEND as incomplete",
                ics.contains("DTEND:20240315T100000Z"));
        assertTrue(ics.trim().endsWith("END:VCALENDAR"));
    }

    @Test
    public void allDayUsesDateValues() {
        String ics = ICalWriter.create("all-day",
                List.of(ICalWriter.date("DTSTART", 1710460800000L),
                        ICalWriter.date("DTEND", 1710547200000L)));
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20240315"));
        assertTrue(ics.contains("DTEND;VALUE=DATE:20240316"));
    }

    @Test
    public void textValuesAreEscaped() {
        String ics = ICalWriter.create("escapes",
                List.of(ICalWriter.text("SUMMARY", "Tea, cake; and\nbiscuits")));
        String backslash = "\\";
        assertTrue(ics.contains("SUMMARY:Tea" + backslash + ", cake" + backslash
                + "; and" + backslash + "nbiscuits"));
    }

    /** What we write must be readable by the parser the bridge and the mirror use. */
    @Test
    public void whatWeWriteWeCanReadBack() {
        String ics = ICalWriter.create("round-trip",
                List.of(ICalWriter.text("SUMMARY", "Round trip"),
                        ICalWriter.timestamp("DTSTART", 1710493200000L),
                        ICalWriter.timestamp("DTEND", 1710496800000L)));
        var values = EventTranslator.toEvent(ics, 7);
        assertTrue(values.isPresent());
        assertEquals("Round trip", values.get().getAsString(
                android.provider.CalendarContract.Events.TITLE));
        assertEquals(Long.valueOf(1710493200000L), values.get().getAsLong(
                android.provider.CalendarContract.Events.DTSTART));
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1))
            count++;
        return count;
    }
}
