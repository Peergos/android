package peergos.android.calendar;

import org.junit.Assert;
import org.junit.Test;

import java.time.Duration;
import java.util.Optional;

/**
 * The DURATION a calendar app on the phone stores for a series. RFC 5545 writes "PT2H"; AOSP
 * Calendar and Etar write the contract's own "P7200S". Both have to read as two hours, or the
 * series reaches every other device an hour long.
 */
public class DurationTest {

    @Test
    public void theContractsOwnForm() {
        Assert.assertEquals(Optional.of(Duration.ofHours(2)), CalendarUploader.duration("P7200S"));
        Assert.assertEquals(Optional.of(Duration.ofDays(1)), CalendarUploader.duration("P1D"));
        Assert.assertEquals(Optional.of(Duration.ofDays(14)), CalendarUploader.duration("P2W"));
        Assert.assertEquals(Optional.of(Duration.ofMinutes(90)), CalendarUploader.duration("P5400S"));
    }

    @Test
    public void theStandardForm() {
        Assert.assertEquals(Optional.of(Duration.ofHours(2)), CalendarUploader.duration("PT2H"));
        Assert.assertEquals(Optional.of(Duration.ofMinutes(90)), CalendarUploader.duration("PT1H30M"));
        Assert.assertEquals(Optional.of(Duration.ofHours(25)), CalendarUploader.duration("P1DT1H"));
        Assert.assertEquals(Optional.of(Duration.ofMinutes(-15)), CalendarUploader.duration("-PT15M"));
    }

    @Test
    public void notADuration() {
        Assert.assertEquals(Optional.empty(), CalendarUploader.duration("2 hours"));
        Assert.assertEquals(Optional.empty(), CalendarUploader.duration("P"));
        Assert.assertEquals(Optional.empty(), CalendarUploader.duration(""));
    }
}
