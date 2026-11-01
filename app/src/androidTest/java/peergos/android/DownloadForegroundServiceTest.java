package peergos.android;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** The foreground service that keeps the process up while DownloadManager fetches from it goes once the download
 *  is over. The download comes from this process over the loopback, as a real one comes from the local server. */
@RunWith(AndroidJUnit4.class)
public class DownloadForegroundServiceTest {

    @Test
    public void itStopsOnceTheDownloadIsDone() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (ServerSocket server = serve("downloaded through the service".getBytes(StandardCharsets.UTF_8))) {
            DownloadManager dm = ctx.getSystemService(DownloadManager.class);
            // localhost, which the app's network security config lets it fetch over plain http
            DownloadManager.Request request = new DownloadManager.Request(
                    Uri.parse("http://localhost:" + server.getLocalPort() + "/service-test.txt"));
            request.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS,
                    "service-test-" + System.currentTimeMillis() + ".txt");
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
    }

    /** Answers every request on the loopback with this body, until closed */
    private static ServerSocket serve(byte[] body) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread accepter = new Thread(() -> {
            while (! server.isClosed()) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                    String line;
                    while ((line = in.readLine()) != null && ! line.isEmpty()) {}
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.write(body);
                } catch (IOException ignored) {}
            }
        });
        accepter.setDaemon(true);
        accepter.start();
        return server;
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
