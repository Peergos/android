package peergos.android;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The foreground service that keeps the process up while DownloadManager fetches from it goes once the download
 *  is over. It waited for a completion broadcast it was never delivered, so it, and its notification, stayed. The
 *  download comes from the host server, as {@link SyncEndToEndTest}'s requests do. */
@RunWith(AndroidJUnit4.class)
public class DownloadForegroundServiceTest {

    @Test
    public void itStopsOnceTheDownloadIsDone() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "10.0.2.2");
        String port = args.getString("peergosPort", "8000");
        DownloadManager dm = ctx.getSystemService(DownloadManager.class);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse("http://" + host + ":" + port + "/"));
        request.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS,
                "service-test-" + System.currentTimeMillis() + ".html");
        long id = dm.enqueue(request);
        DownloadForegroundService.track(ctx, id);

        long end = System.currentTimeMillis() + 60_000;
        int status = -1;
        while (System.currentTimeMillis() < end && status != DownloadManager.STATUS_SUCCESSFUL
                && status != DownloadManager.STATUS_FAILED) {
            Thread.sleep(250);
            status = statusOf(dm, id);
        }
        Assert.assertEquals("the download itself did not complete", DownloadManager.STATUS_SUCCESSFUL, status);
        awaitStopped(ctx, "the download finished");
    }

    @Test
    public void aDownloadNoLongerListedEndsIt() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        // as a download removed from the downloads list is: DownloadManager no longer knows it
        DownloadForegroundService.track(ctx, Long.MAX_VALUE / 3);
        awaitStopped(ctx, "it was started for a download that is not there");
    }

    private static int statusOf(DownloadManager dm, long id) {
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            return c != null && c.moveToFirst() ? c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) : -1;
        }
    }

    private static void awaitStopped(Context ctx, String after) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < end) {
            if (! running(ctx))
                return;
            Thread.sleep(250);
        }
        Assert.fail("The download service is still in the foreground 15 s after " + after);
    }

    @SuppressWarnings("deprecation")
    private static boolean running(Context ctx) {
        // deprecated for other apps' services; an app's own are still listed
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        for (ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE))
            if (info.service.getClassName().equals(DownloadForegroundService.class.getName()) && info.started)
                return true;
        return false;
    }
}
