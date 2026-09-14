package peergos.android.calendar;

import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

/**
 * What the mirror is willing to turn into a Reminders row. The contract's table holds one
 * shape only - minutes before the start - so a trigger it cannot express has to be left
 * alone rather than rounded into one: an alarm moved to a time nobody asked for is worse
 * than an alarm the phone does not show.
 */
public class ReminderMinutesTest {

    private static String event(String... alarmLines) {
        StringBuilder ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n"
                + "UID:reminder-test\r\nDTSTAMP:20260101T090000Z\r\nDTSTART:20260910T100000Z\r\n"
                + "DTEND:20260910T110000Z\r\nSUMMARY:Standup\r\n");
        for (String line : alarmLines)
            ics.append(line).append("\r\n");
        return ics.append("END:VEVENT\r\nEND:VCALENDAR\r\n").toString();
    }

    private static String alarm(String trigger) {
        return "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Standup\r\nTRIGGER:" + trigger + "\r\nEND:VALARM";
    }

    @Test
    public void readsMinutesBeforeTheStart() {
        Assert.assertEquals(Optional.of(15), EventTranslator.reminderMinutes(event(alarm("-PT15M"))));
        Assert.assertEquals(Optional.of(90), EventTranslator.reminderMinutes(event(alarm("-PT1H30M"))));
        Assert.assertEquals(Optional.of(1440), EventTranslator.reminderMinutes(event(alarm("-P1D"))));
        Assert.assertEquals(Optional.of(10080), EventTranslator.reminderMinutes(event(alarm("-P7D"))));
        Assert.assertEquals(Optional.of(0), EventTranslator.reminderMinutes(event(alarm("-PT0M"))));
    }

    @Test
    public void anEventWithoutAnAlarmHasNoReminder() {
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes(event()));
    }

    @Test
    public void leavesTriggersTheContractCannotExpress() {
        // measured from the end
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes(
                event("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM")));
        // an absolute time
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes(
                event("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;VALUE=DATE-TIME:20260910T090000Z\r\nEND:VALARM")));
        // after the start, which the table has no room for
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes(event(alarm("PT15M"))));
        // nonsense
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes(event(alarm("soon"))));
    }

    @Test
    public void takesTheFirstAlarmItCanShow() {
        String ics = event("BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM",
                alarm("-PT20M"), alarm("-PT45M"));
        Assert.assertEquals(Optional.of(20), EventTranslator.reminderMinutes(ics));
    }

    @Test
    public void survivesRubbish() {
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes(""));
        Assert.assertEquals(Optional.empty(), EventTranslator.reminderMinutes("not a calendar"));
    }
}
