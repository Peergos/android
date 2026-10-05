package peergos.android;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Long-running foreground service that drains the configured sync pairs to completion.
 * Must be started while the app is in the foreground (Activity visible / WebView
 * callback) so the BG-FGS restriction does not apply — typically from MainActivity
 * on launch and from the add-pair / sync-now JS bridges.
 */
public class SyncService extends Service {
    /** Guards the two below: whether a pass is running, and the latest start, which the pass
     *  stops the service with when it ends. */
    private static final Object starts = new Object();
    private static boolean running;
    private static int latestStart;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(MainActivity.SYNC_NOTIFICATION_ID, buildNotification(),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        synchronized (starts) {
            latestStart = startId;
            // A start while a pass runs is covered by that pass. Stopping here would stop the
            // service the pass runs in - stopSelf with the newest start id stops it outright -
            // and leave the pass uploading on in a process Android counts as idle.
            if (running)
                return START_NOT_STICKY;
            running = true;
        }
        new Thread(() -> {
            try {
                Path peergosDir = Paths.get(getFilesDir().getAbsolutePath());
                SyncWorker.runSyncOnce(getApplicationContext(), peergosDir);
            } finally {
                synchronized (starts) {
                    running = false;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    // the latest start, so one Android has not handed over yet keeps the service
                    stopSelf(latestStart);
                }
            }
        }, "SyncService").start();
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, MainActivity.SYNC_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sync")
                .setContentText("Sync in progress...")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
