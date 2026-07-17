package peergos.android;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running foreground service that drains the configured sync pairs to completion.
 * Must be started while the app is in the foreground (Activity visible / WebView
 * callback) so the BG-FGS restriction does not apply — typically from MainActivity
 * on launch and from the add-pair / sync-now JS bridges.
 */
public class SyncService extends Service {
    private static final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(MainActivity.SYNC_NOTIFICATION_ID, buildNotification(),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        // SyncWorker.lock would already serialise this, but guarding here lets us
        // stopSelf the redundant start immediately rather than parking a thread.
        if (!running.compareAndSet(false, true)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final int id = startId;
        new Thread(() -> {
            try {
                Path peergosDir = Paths.get(getFilesDir().getAbsolutePath());
                SyncWorker.runSyncOnce(getApplicationContext(), peergosDir);
            } finally {
                running.set(false);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(id);
            }
        }, "SyncService").start();
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, MainActivity.SYNC_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_background)
                .setContentTitle("Sync")
                .setContentText("Sync in progress...")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
