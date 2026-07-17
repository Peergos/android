package peergos.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class UploadProgressNotifier {

    private static final String CHANNEL_ID = "peergos-docs-uploads";
    private static final AtomicInteger nextId = new AtomicInteger(1);

    private final Context ctx;
    private final NotificationManager nm;

    UploadProgressNotifier(Context ctx) {
        this.ctx = ctx;
        this.nm = ctx.getSystemService(NotificationManager.class);
        ensureChannel();
    }

    private void ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Peergos uploads",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("File upload progress");
        nm.createNotificationChannel(ch);
    }

    Handle start(String filename, long size) {
        return new Handle(nextId.incrementAndGet(), filename, size);
    }

    class Handle {
        private final int id;
        private final String filename;
        private final long size;
        private final AtomicLong sent = new AtomicLong();
        private volatile long lastUpdateMs;

        private Handle(int id, String filename, long size) {
            this.id = id;
            this.filename = filename;
            this.size = size;
            postProgress(0);
        }

        void onBytes(long delta) {
            long cumulative = sent.addAndGet(delta);
            long now = System.currentTimeMillis();
            if (now - lastUpdateMs < 200 && cumulative < size) return;
            lastUpdateMs = now;
            postProgress(cumulative);
        }

        private void postProgress(long cumulative) {
            int progress = size > 0 ? (int) Math.min(100, cumulative * 100 / size) : 0;
            try {
                Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher_round)
                        .setContentTitle("Uploading " + filename)
                        .setContentText(progress + "%")
                        .setProgress(100, progress, size <= 0)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build();
                nm.notify(id, n);
            } catch (SecurityException ignored) {
                // POST_NOTIFICATIONS not granted (Android 13+). Silently skip.
            }
        }

        void finish() {
            nm.cancel(id);
        }

        void fail(String message) {
            try {
                Notification n = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                        .setSmallIcon(R.mipmap.ic_launcher_round)
                        .setContentTitle("Upload failed: " + filename)
                        .setContentText(message)
                        .setAutoCancel(true)
                        .build();
                nm.notify(id, n);
            } catch (SecurityException ignored) {}
        }
    }
}
