package peergos.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import peergos.server.Builder;
import peergos.server.JdbcPkiCache;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.net.SyncConfigHandler;
import peergos.server.storage.FileBlockCache;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.PairLogger;
import peergos.server.sync.PairStatus;
import peergos.server.sync.SyncConfig;
import peergos.server.sync.SyncRunner;
import peergos.server.sync.SyncStatus;
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
    /** The key the worker reads its peergos dir from. */
    public static final String PEERGOS_PATH = "PEERGOS_PATH";
    private static final Logger LOG = Logging.LOG();
    /** How soon, and how often, a running pass rechecks that it is still off mobile data. */
    private static final long METERED_FIRST_CHECK_MS = 2_000;
    private static final long METERED_CHECK_MS = 5_000;
    private static final String MOBILE_BLOCKED = "Not syncing on mobile data. Connect to Wi-Fi, "
            + "or allow this folder on mobile data.";

    public static final Object lock = new Object();
    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Path peergosDir = Paths.get(getInputData().getString(PEERGOS_PATH));
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
            // the periodic worker keeps firing while paused, so honour it here too;
            // success rather than failure, or WorkManager would back the schedule off
            if (status.isPaused())
                return true;
            try {
                System.out.println("SYNC: starting work");
                Crypto crypto = Main.initCrypto(new ScryptAndroid());
                Path oldConfigFile = peergosDir.resolve(SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME);
                Path jsonSyncConfig = peergosDir.resolve(SyncConfigHandler.SYNC_CONFIG_FILENAME);

                boolean jsonExists = jsonSyncConfig.toFile().exists();
                SyncConfig syncConfig = jsonExists ?
                        SyncConfig.fromJson((Map<String, Object>) JSONParser.parse(new String(Files.readAllBytes(jsonSyncConfig)))) :
                        SyncConfig.fromArgs(Args.parse(new String[]{"-run-once", "true"}, Optional.of(oldConfigFile), false));
                // a fresh process starts with the flag clear, so take it from the config
                if (syncConfig.paused) {
                    status.pause();
                    return true;
                }

                Args args = Args.parse(new String[0], Optional.of(oldConfigFile), false)
                        .with("PEERGOS_PATH", peergosDir.toString())
                        .with("pki-cache-sql-file", "pki-cache.sqlite");

                URL target = new URL(PeergosApp.readSavedServerUrl(peergosDir).orElse("https://peergos.net"));
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

                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Timer meteredWatch = null;
                AtomicBoolean thisPass = new AtomicBoolean(true);

                // On a metered (mobile) network, drop pairs that aren't allowed there.
                // The periodic worker constraint is CONNECTED, not UNMETERED, so each
                // pair's allowOnMobile flag is what gates mobile-data usage.
                if (isMeteredNetwork(cm)) {
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
                    if (fLinks.size() < links.size()) {
                        // say so on each folder, or a sync that is waiting for Wi-Fi is
                        // indistinguishable from one that is idle and up to date
                        reportMobileDataBlocked(peergosDir, syncConfig);
                        retryWhenUnmetered(context, peergosDir);
                    }
                    if (fLinks.isEmpty()) {
                        System.out.println("SYNC: on metered network and no pairs allow mobile data; skipping");
                        return true;
                    }
                    links = fLinks;
                    localDirs = fLocalDirs;
                    syncLocalDeletes = fSyncLocalDeletes;
                    syncRemoteDeletes = fSyncRemoteDeletes;
                } else if (cm != null && syncConfig.allowOnMobile.contains(false)) {
                    // Metered-ness is only sampled above, at pass start, so without this a
                    // mid-pass handover to mobile (walking out of Wi-Fi range) would keep
                    // spending mobile data for the rest of a long pass. A default network
                    // callback missed such a handover, letting a pass upload for minutes on
                    // mobile, so the same check the pass start uses is sampled instead.
                    meteredWatch = new Timer("peergos-metered-watch", true);
                    meteredWatch.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            // cancelling the timer cannot interrupt a tick already running,
                            // so the pass hands the flag over rather than stopping the next one
                            synchronized (thisPass) {
                                if (thisPass.get() && isMeteredNetwork(cm))
                                    status.cancel(MOBILE_BLOCKED);
                            }
                        }
                    }, METERED_FIRST_CHECK_MS, METERED_CHECK_MS);
                }

                try {
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
                } finally {
                    if (meteredWatch != null) {
                        synchronized (thisPass) {
                            thisPass.set(false);
                        }
                        meteredWatch.cancel();
                    }
                    // read before resume(), which clears the reason
                    if (status.getStopReason().filter(MOBILE_BLOCKED::equals).isPresent())
                        retryWhenUnmetered(context, peergosDir);
                    // syncDirs only self-clears cancellation when it starts another pair, so a
                    // cancel during the last one would leave the shared status wedged.
                    status.resume();
                }
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

    /** Wi-Fi can come back long before the next periodic run, so let WorkManager start a
     *  pass as soon as an unmetered network is up. Unique work, so repeated blocks replace
     *  each other rather than stack, and it outlives this process. */
    private static void retryWhenUnmetered(Context context, Path peergosDir) {
        WorkManager.getInstance(context).enqueueUniqueWork("peergos-sync-unmetered",
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.UNMETERED)
                                .build())
                        .setInputData(new Data.Builder()
                                .putString(PEERGOS_PATH, peergosDir.toString())
                                .build())
                        .build());
    }

    private static void reportMobileDataBlocked(Path peergosDir, SyncConfig config) {
        for (int i = 0; i < config.links.size(); i++) {
            if (config.allowOnMobile.get(i))
                continue;
            PairStatus pair = new PairStatus(peergosDir,
                    PairLogger.hash(config.remotePaths.get(i), config.localDirs.get(i)));
            pair.setError(MOBILE_BLOCKED);
            pair.setStatus(SyncStatus.ERROR);
        }
    }

    private static boolean isMeteredNetwork(ConnectivityManager cm) {
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
