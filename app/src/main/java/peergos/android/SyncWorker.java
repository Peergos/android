package peergos.android;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.peergos.util.Futures;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import peergos.server.Builder;
import peergos.server.JdbcPkiCache;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.net.SyncConfigHandler;
import peergos.server.storage.FileBlockCache;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.SyncConfig;
import peergos.server.sync.SyncRunner;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.CryptreeCache;
import peergos.shared.NetworkAccess;
import peergos.shared.OnlineState;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.OfflineCorenode;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.mutable.CachingPointers;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.MutableTreeImpl;
import peergos.shared.user.WriteSynchronizer;

public class SyncWorker extends Worker {
    public static final SyncRunner.StatusHolder status = new SyncRunner.StatusHolder();

    public static final Object lock = new Object();
    private final WorkerParameters params;
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.params = workerParams;
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (lock) {
            try {
                System.out.println("SYNC: starting work");
                showNotification("Sync", "Sync in progress...", MainActivity.SYNC_NOTIFICATION_ID, NotificationCompat.PRIORITY_MIN);
                Data params = this.params.getInputData();
                int sleepMillis = params.getInt("sleep", 0);
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException ignored) {
                }
                Path peergosDir = Paths.get(params.getString("PEERGOS_PATH"));
                Crypto crypto = Main.initCrypto(new ScryptAndroid());
                Path oldConfigFile = peergosDir.resolve(SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME);
                Path jsonSyncConfig = peergosDir.resolve(SyncConfigHandler.SYNC_CONFIG_FILENAME);

                boolean jsonExists = jsonSyncConfig.toFile().exists();
                SyncConfig syncConfig = jsonExists ?
                        SyncConfig.fromJson((Map<String, Object>) JSONParser.parse(new String(Files.readAllBytes(jsonSyncConfig)))) :
                        SyncConfig.fromArgs(Args.parse(new String[]{"-run-once", "true"}, Optional.of(oldConfigFile), false));

                Args args = Args.parse(new String[0], Optional.of(oldConfigFile), false)
                        .with("PEERGOS_PATH", peergosDir.toString())
                        .with("pki-cache-sql-file", "pki-cache.sqlite");

                URL target = new URL(args.getArg("peergos-url", "https://peergos.net"));
                HttpPoster poster = new AndroidPoster(target, true, Optional.empty(), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-android"));
                ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
                CoreNode directCore = NetworkAccess.buildDirectCorenode(poster);
                OnlineState online = new OnlineState(() -> Futures.of(true));
                OfflineCorenode core = new OfflineCorenode(directCore, new JdbcPkiCache(Builder.getDBConnector(args, "pki-cache-sql-file"), Builder.getSqlCommands(args)), online);
                ContentAddressedStorage s3 = NetworkAccess.buildDirectS3Blockstore(localDht, core, poster, true, crypto.hasher).join();
                FileBlockCache blockCache = new FileBlockCache(peergosDir.resolve(Paths.get("blocks", "cache")),
                        10 * 1024 * 1024 * 1024L);
                ContentAddressedStorage storage = new UnauthedCachingStorage(s3, blockCache, crypto.hasher);

                MutablePointers mutable = new CachingPointers(new HttpMutablePointers(poster, poster), 5_000);

                WriteSynchronizer synchronizer = new WriteSynchronizer(mutable, storage, crypto.hasher);
                MutableTreeImpl tree = new MutableTreeImpl(mutable, storage, crypto.hasher, synchronizer);
                NetworkAccess network = new NetworkAccess(core, null, null, storage, null, Optional.empty(),
                        mutable, tree, synchronizer, null, null, null, crypto.hasher,
                        Collections.emptyList(), new CryptreeCache(50), false);
                if (syncConfig.links.isEmpty()) {
                    System.out.println("No sync args");
                    return Result.success();
                }
                List<String> links = syncConfig.links;
                List<String> localDirs = syncConfig.localDirs;
                List<Boolean> syncLocalDeletes = syncConfig.syncLocalDeletes;
                List<Boolean> syncRemoteDeletes = syncConfig.syncRemoteDeletes;
                int maxDownloadParallelism = syncConfig.maxDownloadParallelism;
                int minFreeSpacePercent = syncConfig.minFreeSpacePercent;

                DirectorySync.syncDirs(links, localDirs, syncLocalDeletes, syncRemoteDeletes,
                        maxDownloadParallelism, minFreeSpacePercent, true, uri -> new AndroidSyncFileSystem(Uri.parse(uri),
                                getApplicationContext(), crypto), peergosDir,
                        status,
                        m -> {
                            status.setStatus(m);
                        },
                        e -> {
                            if (e != null) {
                                Throwable cause = getCause(e);
                                if (!(cause instanceof UnknownHostException)) {
                                    showNotification("Sync error", cause.getMessage(), MainActivity.SYNC_NOTIFICATION_ERROR_ID, NotificationCompat.PRIORITY_DEFAULT);
                                    status.setError(e.getMessage());
                                }
                            }
                        }, network, crypto);
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (Exception e) {
                if (getCause(e) instanceof UnknownHostException)
                    return Result.failure();
                String msg = e.getMessage();
                if (msg != null && !msg.trim().isEmpty()) {
                    status.setError(msg);
                    showNotification("Sync error", msg, MainActivity.SYNC_NOTIFICATION_ERROR_ID, NotificationCompat.PRIORITY_DEFAULT);
                }
                return Result.failure();
            } finally {
                closeNotification(MainActivity.SYNC_NOTIFICATION_ID);
            }

            return Result.success();
        }
    }

    private static Throwable getCause(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null)
            return t;
        if (t instanceof ExecutionException)
            return getCause(cause);
        if (t instanceof RuntimeException)
            return getCause(cause);
        if (t instanceof CompletionException)
            return getCause(cause);
        return cause;
    }

    public void closeNotification(int notificationId) {
        NotificationManagerCompat mgr = NotificationManagerCompat.from(getApplicationContext());
        mgr.cancel(notificationId);
    }

    public void showNotification(String title, String text, int notificationId, int priority) {
        DirectorySync.log(text);
//        Context context = getApplicationContext();
        // This PendingIntent can be used to cancel the worker
//        PendingIntent intent = WorkManager.getInstance(context)
//                .createCancelPendingIntent(getId());

        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), MainActivity.SYNC_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_background)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                // Add the cancel action to the notification which can
                // be used to cancel the worker
//                .addAction(android.R.drawable.ic_delete, "Cancel", intent)
                .setPriority(priority);

        NotificationManagerCompat mgr = NotificationManagerCompat.from(getApplicationContext());
        if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // notificationId is a unique int for each notification that you must define.
        Notification notif = builder.build();
        mgr.notify(notificationId, notif);
        if (AppLifecycleObserver.inForeground.get())
            setForegroundAsync(new ForegroundInfo(notificationId, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC));
    }
}
