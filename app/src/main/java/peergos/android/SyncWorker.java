package peergos.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.peergos.util.Futures;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import peergos.server.Builder;
import peergos.server.JdbcPkiCache;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.storage.FileBlockCache;
import peergos.server.sync.DirectorySync;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.CryptreeCache;
import peergos.shared.NetworkAccess;
import peergos.shared.OnlineState;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.OfflineCorenode;
import peergos.shared.mutable.CachingPointers;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.MutableTreeImpl;
import peergos.shared.user.WriteSynchronizer;

public class SyncWorker extends Worker {

    private final WorkerParameters params;
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.params = workerParams;
    }

    @NonNull
    @Override
    public Result doWork() {
        System.out.println("SYNC: starting work");
        showNotification("Sync", "Starting sync");
        Data params = this.params.getInputData();
        int sleepMillis = params.getInt("sleep", 0);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ignored) {}
        Path peergosDir = Paths.get(params.getString("PEERGOS_PATH"));
        Crypto crypto = Main.initCrypto(new CachingHasher(peergosDir.resolve("scrypt-cache.txt").toFile()));
        Path configFile = peergosDir.resolve("config");
        Args args = Args.parse(new String[0], Optional.of(configFile), false);
        try {
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
            if (! args.hasArg("links")) {
                System.out.println("No sync args");
                return Result.success();
            }
            List<String> links = new ArrayList<>(Arrays.asList(args.getArg("links").split(",")));
            List<String> localDirs = new ArrayList<>(Arrays.asList(args.getArg("local-dirs").split(",")));
            List<Boolean> syncLocalDeletes = args.hasArg("sync-local-deletes") ?
                    new ArrayList<>(Arrays.stream(args.getArg("sync-local-deletes").split(","))
                            .map(Boolean::parseBoolean)
                            .collect(Collectors.toList())) :
                    IntStream.range(0, links.size())
                            .mapToObj(x -> true)
                            .collect(Collectors.toList());
            List<Boolean> syncRemoteDeletes = args.hasArg("sync-remote-deletes") ?
                    new ArrayList<>(Arrays.stream(args.getArg("sync-remote-deletes").split(","))
                            .map(Boolean::parseBoolean)
                            .collect(Collectors.toList())) :
                    IntStream.range(0, links.size())
                            .mapToObj(x -> true)
                            .collect(Collectors.toList());
            int maxDownloadParallelism = args.getInt("max-parallelism", 32);
            int minFreeSpacePercent = args.getInt("min-free-space-percent", 5);

            DirectorySync.syncDirs(links, localDirs, syncLocalDeletes, syncRemoteDeletes,
                    maxDownloadParallelism, minFreeSpacePercent, true, uri -> new AndroidSyncFileSystem(Uri.parse(uri), getApplicationContext(), crypto.hasher), peergosDir, m -> showNotification("Sync", m), network, crypto);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        closeNotification();

        return Result.success();
    }

    public void closeNotification() {
        NotificationManagerCompat mgr = NotificationManagerCompat.from(getApplicationContext());
        mgr.cancel(MainActivity.SYNC_NOTIFICATION_ID);
    }

    public void showNotification(String title, String text) {
        DirectorySync.log(text);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), MainActivity.SYNC_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_background)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManagerCompat mgr = NotificationManagerCompat.from(getApplicationContext());
        if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // notificationId is a unique int for each notification that you must define.
        mgr.notify(MainActivity.SYNC_NOTIFICATION_ID, builder.build());
    }
}
