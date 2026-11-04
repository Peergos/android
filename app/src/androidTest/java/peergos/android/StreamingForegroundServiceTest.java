package peergos.android;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The foreground service that keeps the process out of Doze while another app reads a file through the drive */
@RunWith(AndroidJUnit4.class)
public class StreamingForegroundServiceTest {

    /** The service gone with a file still open, as when the system ends it at the data-sync time limit: a file opened
     *  after that starts it again, rather than only once every file open before has been closed. */
    @Test
    public void aFileOpenedOnceTheServiceIsGoneStartsItAgain() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StreamingForegroundService.acquire(ctx);
        try {
            awaitForeground(ctx, true, "a file was opened");
            // as at the time limit, where the service stops itself
            ctx.stopService(new Intent(ctx, StreamingForegroundService.class));
            awaitForeground(ctx, false, "it was stopped");
            StreamingForegroundService.acquire(ctx);
            try {
                awaitForeground(ctx, true, "another file was opened");
            } finally {
                StreamingForegroundService.release(ctx);
            }
        } finally {
            StreamingForegroundService.release(ctx);
        }
        awaitForeground(ctx, false, "the files were closed");
    }

    private static void awaitForeground(Context ctx, boolean expected, String after) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (inForeground(ctx) == expected)
                return;
            Thread.sleep(100);
        }
        Assert.fail("The streaming service is " + (expected ? "not" : "still") + " in the foreground 15 s after " + after);
    }

    @SuppressWarnings("deprecation")
    private static boolean inForeground(Context ctx) {
        // deprecated for other apps' services; an app's own are still listed
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE))
            if (info.service.getClassName().equals(StreamingForegroundService.class.getName()) && info.started
                    && info.foreground)
                return true;
        return false;
    }
}
