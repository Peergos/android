package peergos.android;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Long-running foreground service that drains the configured sync pairs to completion.
 * Started while the app is in the foreground (Activity visible / WebView callback) -
 * MainActivity on launch and the add-pair / sync-now JS bridges - and by the scheduled
 * sync, where Android lets the app start one from the background.
 */
public class SyncService extends Service {
    /** Guards the two below: whether a pass is running, and the latest start, which the pass
     *  stops the service with when it ends. */
    private static final Object starts = new Object();
    private static boolean running;
    private static int latestStart;

    /** A data sync gets six hours a day in the foreground, and once they are spent Android
     *  refuses it until the app is next on screen. The scheduled sync runs its own passes
     *  meanwhile, rather than starting a service that cannot go into the foreground. */
    private static volatile boolean foregroundSpent;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(MainActivity.SYNC_NOTIFICATION_ID, notification(this),
                    FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (IllegalStateException refused) {
            // ForegroundServiceStartNotAllowedException: the day's time is spent. It is thrown
            // here rather than to whoever started the service, and uncaught it ends the process.
            foregroundSpent = true;
            stopSelf(startId);
            SyncWorker.retrySoon(getApplicationContext(), Paths.get(getFilesDir().getAbsolutePath()));
            return START_NOT_STICKY;
        }
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

    /**
     * Starts the service from the background, which Android allows once battery optimisation is
     * off for the app.
     *
     * @return whether it started; where Android refused, the caller syncs some other way
     */
    static boolean startFromBackground(Context context) {
        if (foregroundSpent)
            return false;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, SyncService.class));
            return true;
        } catch (IllegalStateException refused) {
            // ForegroundServiceStartNotAllowedException, which is an IllegalStateException
            return false;
        }
    }

    /** Android ends a data sync service after six hours of a day. Stop the pass where it is, as
     *  the process is ended otherwise; the next scheduled sync carries on from there. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        foregroundSpent = true;
        SyncWorker.status.cancel("Android limits how long a sync may run each day. It will carry on later.");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** The app is on screen, which gives a data sync its time in the foreground back. */
    static void appOnScreen() {
        foregroundSpent = false;
    }

    /** The channel the notification was on before, which sounded. Android keeps the importance a
     *  channel was created with, so the quiet one is a new channel and this one goes. */
    private static final String OLD_CHANNEL_ID = "sync-updates";

    /** The channel is created here as well as by the activity, since a sync started in the
     *  background can be the first thing the process does. Low importance, as for any
     *  transfer in the background: in the shade while it runs, but no sound, since every
     *  scheduled sync posts it again. */
    static void createChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.deleteNotificationChannel(OLD_CHANNEL_ID);
        NotificationChannel channel = new NotificationChannel(MainActivity.SYNC_CHANNEL_ID, "Sync",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("While folders are syncing");
        manager.createNotificationChannel(channel);
    }

    static Notification notification(Context context) {
        createChannel(context);
        return new NotificationCompat.Builder(context, MainActivity.SYNC_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Sync")
                .setContentText("Sync in progress...")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
    }
}
