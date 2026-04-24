package peergos.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicInteger;

public class DownloadForegroundService extends Service {

    public static final String CHANNEL_ID = "download-foreground";
    public static final int NOTIFICATION_ID = 80;
    public static final String ACTION_STOP = "peergos.android.DOWNLOAD_STOP";

    private static final AtomicInteger activeDownloads = new AtomicInteger(0);

    public static void increment() { activeDownloads.incrementAndGet(); }

    public static void decrement() { activeDownloads.decrementAndGet(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (activeDownloads.get() <= 0)
                stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Peergos")
                .setContentText("Downloading...")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_NOT_STICKY;
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
