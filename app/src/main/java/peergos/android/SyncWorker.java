package peergos.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.peergos.util.Futures;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import peergos.server.util.Logging;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.OnlineState;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.OfflineCorenode;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.mutable.CachingPointers;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.mutable.MutablePointers;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.RetryStorage;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.user.HttpPoster;

public class SyncWorker extends Worker {
    public static final SyncRunner.StatusHolder status = new SyncRunner.StatusHolder();
    private static final Logger LOG = Logging.LOG();

    public static final Object lock = new Object();
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Path peergosDir = Paths.get(getInputData().getString("PEERGOS_PATH"));
        return runSyncOnce(getApplicationContext(), peergosDir) ? Result.success() : Result.failure();
    }

    /**
     * Run a single sync pass to completion (or first error). Holds the global sync lock
     * so it cooperates with SyncService and other periodic invocations — at most one
     * sync runs at a time across the process.
     *
     * @return true on clean completion, false on UnknownHostException / fatal failure
     */
    public static boolean runSyncOnce(Context context, Path peergosDir) {
        synchronized (lock) {
            try {
                System.out.println("SYNC: starting work");
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
                        MainActivity.MAX_BLOCK_CACHE_SIZE);
                ContentAddressedStorage storage = new UnauthedCachingStorage(s3, blockCache, crypto.hasher);

                MutablePointers mutable = new CachingPointers(new HttpMutablePointers(poster, poster), 5_000);

                NetworkAccess network = NetworkAccess.buildBuffered(new RetryStorage(storage, 5), null, core, null,
                        mutable, 5_000, null, null, null, null,
                        crypto.hasher, Collections.emptyList(), false);
                if (syncConfig.links.isEmpty()) {
                    System.out.println("No sync args");
                    return true;
                }
                List<String> links = syncConfig.links;
                List<String> localDirs = syncConfig.localDirs;
                List<Boolean> syncLocalDeletes = syncConfig.syncLocalDeletes;
                List<Boolean> syncRemoteDeletes = syncConfig.syncRemoteDeletes;
                int maxDownloadParallelism = syncConfig.maxDownloadParallelism;
                int minFreeSpacePercent = syncConfig.minFreeSpacePercent;

                // On a metered (mobile) network, drop pairs that aren't allowed there.
                // The periodic worker constraint is CONNECTED, not UNMETERED, so each
                // pair's allowOnMobile flag is what gates mobile-data usage.
                if (isMeteredNetwork(context)) {
                    List<String> fLinks = new ArrayList<>();
                    List<String> fLocalDirs = new ArrayList<>();
                    List<Boolean> fSyncLocalDeletes = new ArrayList<>();
                    List<Boolean> fSyncRemoteDeletes = new ArrayList<>();
                    for (int i = 0; i < links.size(); i++) {
                        if (syncConfig.allowOnMobile.get(i)) {
                            fLinks.add(links.get(i));
                            fLocalDirs.add(localDirs.get(i));
                            fSyncLocalDeletes.add(syncLocalDeletes.get(i));
                            fSyncRemoteDeletes.add(syncRemoteDeletes.get(i));
                        }
                    }
                    if (fLinks.isEmpty()) {
                        System.out.println("SYNC: on metered network and no pairs allow mobile data; skipping");
                        return true;
                    }
                    links = fLinks;
                    localDirs = fLocalDirs;
                    syncLocalDeletes = fSyncLocalDeletes;
                    syncRemoteDeletes = fSyncRemoteDeletes;
                }

                DirectorySync.syncDirs(links, localDirs, syncLocalDeletes, syncRemoteDeletes,
                        maxDownloadParallelism, minFreeSpacePercent, true, uri -> new AndroidSyncFileSystem(Uri.parse(uri),
                                context, crypto), peergosDir,
                        status,
                        m -> {
                            status.setStatus(m);
                            LOG.info(m);
                        },
                        e -> {
                            if (e != null) {
                                Throwable cause = getCause(e);
                                if (!(cause instanceof UnknownHostException)) {
                                    status.setError(cause.getMessage());
                                }
                                LOG.log(Level.WARNING, cause, cause::getMessage);
                            }
                        }, network, crypto);
                return true;
            } catch (MalformedURLException e) {
                e.printStackTrace();
                return true;
            } catch (Exception e) {
                Throwable cause = getCause(e);
                if (cause instanceof UnknownHostException)
                    return false;
                String msg = cause.getMessage();
                if (msg != null && !msg.trim().isEmpty())
                    status.setError(msg);
                LOG.log(Level.WARNING, cause, cause::getMessage);
                return false;
            }
        }
    }

    private static boolean isMeteredNetwork(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) return false;
        // NET_CAPABILITY_NOT_METERED is set on Wi-Fi/Ethernet by default; mobile data
        // and metered hotspots lack it.
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
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
}
