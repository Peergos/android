package peergos.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the app's process exempt from Doze while a SAF consumer (e.g. the system
 *  video player) holds an open proxy file descriptor against PeergosDocumentsProvider.
 *  Without this, background network is suspended a few minutes after the WebView
 *  loses visibility and chunk fetches fail with "Unable to resolve host". */
public class StreamingForegroundService extends Service {

    public static final String CHANNEL_ID = "peergos-streaming";
    public static final int NOTIFICATION_ID = 81;
    public static final String ACTION_STOP = "peergos.android.STREAMING_STOP";

    private static final AtomicInteger openFds = new AtomicInteger(0);

    /** Increment the open-FD count and start the service on the 0→1 transition. */
    public static void acquire(Context ctx) {
        if (openFds.getAndIncrement() == 0) {
            ctx.startForegroundService(new Intent(ctx, StreamingForegroundService.class));
        }
    }

    /** Decrement the open-FD count and stop the service on the 1→0 transition. */
    public static void release(Context ctx) {
        if (openFds.decrementAndGet() <= 0) {
            Intent stop = new Intent(ctx, StreamingForegroundService.class);
            stop.setAction(ACTION_STOP);
            ctx.startService(stop);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (openFds.get() <= 0) stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Peergos")
                .setContentText("Streaming files...")
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
                    CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
