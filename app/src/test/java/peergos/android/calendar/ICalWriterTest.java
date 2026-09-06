package peergos.android.calendar;

import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * What a phone-side edit is allowed to do to a stored file. The event is the user's, but
 * so is everything else in it: an alarm, an attendee, a property this app has never heard
 * of. A patch that reaches into an alarm and rewrites its text has corrupted the file just
 * as surely as one that drops it.
 */
public class ICalWriterTest {

    private static final String WITH_ALARM =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
            + "UID:with-alarm\r\nDTSTAMP:20260101T090000Z\r\nDTSTART:20260910T100000Z\r\n"
            + "DTEND:20260910T110000Z\r\nSUMMARY:Standup\r\n"
            + "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Standup\r\nTRIGGER:-PT15M\r\nEND:VALARM\r\n"
            + "END:VEVENT\r\nEND:VCALENDAR\r\n";

    private static List<String> lines(String ics) {
        return List.of(ics.split("\r\n"));
    }

    @Test
    public void patchLeavesAnAlarmsOwnPropertiesAlone() {
        String patched = ICalWriter.patch(WITH_ALARM,
                List.of(ICalWriter.text("SUMMARY", "Standup, moved"),
                        ICalWriter.text("DESCRIPTION", "Notes for the event")),
                Collections.emptyList());

        Assert.assertTrue(patched.contains("SUMMARY:Standup\\, moved"));
        // the alarm's own text, not the event's
        Assert.assertTrue(patched.contains("DESCRIPTION:Standup\r\nTRIGGER:-PT15M"));
        Assert.assertTrue(patched.contains("DESCRIPTION:Notes for the event"));
        Assert.assertEquals(1, lines(patched).stream().filter(l -> l.equals("BEGIN:VALARM")).count());
    }

    @Test
    public void patchAddsWhatWasMissingToTheEventItself() {
        String patched = ICalWriter.patch(WITH_ALARM,
                List.of(ICalWriter.text("LOCATION", "Room 2")), Collections.emptyList());
        List<String> out = lines(patched);
        Assert.assertTrue(out.contains("LOCATION:Room 2"));
        Assert.assertTrue(out.indexOf("LOCATION:Room 2") > out.indexOf("END:VALARM"));
        Assert.assertTrue(out.indexOf("LOCATION:Room 2") < out.indexOf("END:VEVENT"));
    }

    @Test
    public void patchRemovesOnlyTheEventsOwnProperty() {
        String patched = ICalWriter.patch(WITH_ALARM, Collections.emptyList(), List.of("DESCRIPTION"));
        Assert.assertTrue(patched.contains("DESCRIPTION:Standup\r\nTRIGGER:-PT15M"));
    }

    @Test
    public void aReminderSetOnThePhoneReplacesTheOneInTheFile() {
        String written = ICalWriter.withReminder(WITH_ALARM, Optional.of(30), "Standup");
        Assert.assertTrue(written.contains("TRIGGER:-PT30M"));
        Assert.assertFalse(written.contains("TRIGGER:-PT15M"));
        Assert.assertEquals(1, lines(written).stream().filter(l -> l.equals("BEGIN:VALARM")).count());
    }

    @Test
    public void clearingItOnThePhoneClearsItInTheFile() {
        String written = ICalWriter.withReminder(WITH_ALARM, Optional.empty(), "Standup");
        Assert.assertFalse(written.contains("VALARM"));
        Assert.assertTrue(written.contains("SUMMARY:Standup"));
    }

    @Test
    public void anAlarmThePhoneCannotShowIsLeftWhereItIs() {
        String fromTheEnd = WITH_ALARM.replace("TRIGGER:-PT15M", "TRIGGER;RELATED=END:-PT5M");
        String written = ICalWriter.withReminder(fromTheEnd, Optional.empty(), "Standup");
        Assert.assertTrue(written.contains("TRIGGER;RELATED=END:-PT5M"));

        String alsoOurs = ICalWriter.withReminder(fromTheEnd, Optional.of(10), "Standup");
        Assert.assertTrue(alsoOurs.contains("TRIGGER;RELATED=END:-PT5M"));
        Assert.assertTrue(alsoOurs.contains("TRIGGER:-PT10M"));
        Assert.assertEquals(2, lines(alsoOurs).stream().filter(l -> l.equals("BEGIN:VALARM")).count());
    }

    @Test
    public void anEventWithNoAlarmGainsTheOneThePhoneHas() {
        String plain = WITH_ALARM.replaceAll("(?s)BEGIN:VALARM.*END:VALARM\r\n", "");
        String written = ICalWriter.withReminder(plain, Optional.of(60), "Standup");
        Assert.assertTrue(written.contains("BEGIN:VALARM"));
        Assert.assertTrue(written.contains("ACTION:DISPLAY"));
        Assert.assertTrue(written.contains("TRIGGER:-PT60M"));
        Assert.assertTrue(written.contains("DESCRIPTION:Standup"));
        List<String> out = lines(written);
        Assert.assertTrue(out.indexOf("BEGIN:VALARM") < out.indexOf("END:VEVENT"));
    }

    @Test
    public void whatTheMirrorWritesIsWhatItReadsBack() {
        String written = ICalWriter.withReminder(WITH_ALARM, Optional.of(45), "Standup");
        Assert.assertEquals(Optional.of(45), EventTranslator.reminderMinutes(written));
    }
}
