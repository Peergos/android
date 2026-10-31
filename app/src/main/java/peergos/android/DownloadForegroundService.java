package peergos.android;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.Set;

/** Keeps the process in the foreground while DownloadManager fetches files from the server in it.
 *
 *  Over when every download it was started for has finished, which it asks DownloadManager about. The completion
 *  broadcast it waited for before comes from another app, and a receiver registered as not exported never hears
 *  it: the notification and the service stayed up after every download. */
public class DownloadForegroundService extends Service {

    public static final String CHANNEL_ID = "download-foreground";
    public static final int NOTIFICATION_ID = 80;
    private static final String EXTRA_DOWNLOAD_ID = "peergos.android.DOWNLOAD_ID";
    private static final long CHECK_MILLIS = 2_000;

    /** Started for and not finished. On the main thread only, as are starts and checks. */
    private final Set<Long> downloads = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::check;
    private int lastStartId;

    /** Keeps the process up for a download DownloadManager has just been given. */
    public static void track(Context context, long downloadId) {
        Intent intent = new Intent(context, DownloadForegroundService.class);
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Peergos")
                .setContentText("Downloading...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        if (intent != null && intent.hasExtra(EXTRA_DOWNLOAD_ID))
            downloads.add(intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1));
        handler.removeCallbacks(check);
        handler.post(check);
        return START_NOT_STICKY;
    }

    private void check() {
        Set<Long> running = new HashSet<>();
        boolean finished = false;
        if (! downloads.isEmpty()) {
            long[] ids = downloads.stream().mapToLong(Long::longValue).toArray();
            DownloadManager dm = getSystemService(DownloadManager.class);
            try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(ids))) {
                while (c != null && c.moveToNext()) {
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL)
                        finished = true;
                    else if (status != DownloadManager.STATUS_FAILED)
                        running.add(c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
                }
            }
        }
        if (finished)
            Toast.makeText(getApplicationContext(), "Downloading Complete", Toast.LENGTH_SHORT).show();
        // one removed from the downloads list is not listed at all, and as over as one that failed
        downloads.retainAll(running);
        if (! downloads.isEmpty()) {
            handler.postDelayed(check, CHECK_MILLIS);
            return;
        }
        // a start for another download may be on its way, and stopping would drop it
        if (stopSelfResult(lastStartId))
            stopForeground(STOP_FOREGROUND_REMOVE);
    }

    /** The day's allowance for data sync is used up, and the system wants the service gone. DownloadManager goes
     *  on, though what it fetches from this process may stall once the process is in the background. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        handler.removeCallbacks(check);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(check);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
