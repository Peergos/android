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
import java.time.LocalDateTime;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    /** Set while the app is on screen and keeping its own gap between passes. Scheduled
     *  retries stand down then, or the two chains together sync sooner than either means to. */
    public static final AtomicBoolean onScreenCadence = new AtomicBoolean(false);

    /** When the last pass finished, whoever ran it. The on-screen gap counts from any pass,
     *  so a sync started from elsewhere is not followed by another one seconds later. */
    public static final AtomicLong lastPassEndMs = new AtomicLong(0);

    /** Passes running now. A pass waits for the one ahead of it on the lock, so a ticker that
     *  joined that queue would sync again the moment the first one finished. */
    public static final AtomicInteger passesInFlight = new AtomicInteger(0);
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
        // a pass that ends badly queues its own follow-up, which also covers the passes run
        // by the foreground service, so this unit of work is done either way
        runSyncOnce(getApplicationContext(), peergosDir);
        return Result.success();
    }

    /**
     * Run a single sync pass to completion (or first error). Holds the global sync lock
     * so it cooperates with SyncService and other periodic invocations — at most one
     * sync runs at a time across the process.
     *
     * @return whether every folder in the pass synced, so a false asks for another go
     */
    public static boolean runSyncOnce(Context context, Path peergosDir) {
        passesInFlight.incrementAndGet();
        try {
            return runPass(context, peergosDir);
        } finally {
            lastPassEndMs.set(System.currentTimeMillis());
            passesInFlight.decrementAndGet();
        }
    }

    private static boolean runPass(Context context, Path peergosDir) {
        synchronized (lock) {
            // the scheduled work keeps firing while paused, so honour the flag here too
            if (status.isPaused())
                return true;
            SyncConfig syncConfig = null;
            try {
                System.out.println("SYNC: starting work");
                Crypto crypto = Main.initCrypto(new ScryptAndroid());
                Path oldConfigFile = peergosDir.resolve(SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME);
                Path jsonSyncConfig = peergosDir.resolve(SyncConfigHandler.SYNC_CONFIG_FILENAME);

                boolean jsonExists = jsonSyncConfig.toFile().exists();
                syncConfig = jsonExists ?
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
                SyncConfig config = syncConfig;
                int maxDownloadParallelism = config.maxDownloadParallelism;
                int minFreeSpacePercent = config.minFreeSpacePercent;

                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                // The periodic worker constraint is CONNECTED, not UNMETERED, so each pair's
                // allowOnMobile flag is what gates mobile data use.
                boolean metered = isMeteredNetwork(cm);
                LocalDateTime passStart = LocalDateTime.now();
                List<Integer> pairs = metered ? pairsAllowedOnMobile(config) : allPairs(config);
                if (pairs.size() < config.links.size()) {
                    // this pass cannot check them, so they need the user: say so on each one, or
                    // a folder waiting for Wi-Fi looks the same as one that is up to date
                    stampPairs(peergosDir, config, pairsBlockedOnMobile(config), MOBILE_BLOCKED, SyncStatus.ERROR);
                    retryWhenUnmetered(context, peergosDir);
                }
                if (pairs.isEmpty()) {
                    // stamps the time as well, so the next check shows as having happened rather
                    // than the app looking stalled at whenever it last managed to sync
                    status.setStatus(MOBILE_BLOCKED);
                    status.setStatus(SyncStatus.ERROR);
                    return true;
                }

                boolean ranClean = false;
                try {
                    while (true) {
                        List<Integer> passPairs = pairs;
                        Timer meteredWatch = null;
                        AtomicBoolean thisPass = new AtomicBoolean(true);
                        // Metered-ness is sampled, because a default network callback missed a
                        // handover and let a pass spend minutes of mobile data. Only needed while
                        // a folder that disallows mobile is in this pass.
                        if (! metered && cm != null && config.allowOnMobile.contains(false)) {
                            meteredWatch = new Timer("peergos-metered-watch", true);
                            meteredWatch.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    // cancelling the timer cannot interrupt a tick already running,
                                    // so the pass hands the flag over rather than stopping the next
                                    synchronized (thisPass) {
                                        if (thisPass.get() && isMeteredNetwork(cm))
                                            status.cancel(MOBILE_BLOCKED);
                                    }
                                }
                            }, METERED_FIRST_CHECK_MS, METERED_CHECK_MS);
                        }
                        try {
                            // the desktop runner clears the error each pass; without it here one
                            // failure keeps the app reporting a problem it has recovered from
                            status.setError(null);
                            // set before the first remote call, so a cycle is visible even when it
                            // is a retry that fails again in the same place
                            status.setStatus(SyncStatus.SYNCING);
                            stampPairs(peergosDir, config, passPairs, null, SyncStatus.SYNCING);
                            ranClean = DirectorySync.syncDirs(pick(config.links, passPairs), pick(config.localDirs, passPairs),
                                    pick(config.syncLocalDeletes, passPairs), pick(config.syncRemoteDeletes, passPairs),
                                    maxDownloadParallelism, minFreeSpacePercent, true,
                                    uri -> new AndroidSyncFileSystem(Uri.parse(uri), context, crypto), peergosDir,
                                    status,
                                    m -> {
                                        status.setStatus(m);
                                        LOG.info(m);
                                    },
                                    e -> {
                                        if (e == null)
                                            return;
                                        Throwable cause = getCause(e);
                                        String why = DirectorySync.describeError(cause);
                                        if (e instanceof DirectorySync.PairFailure)
                                            stampPairs(peergosDir, config,
                                                    Collections.singletonList(passPairs.get(((DirectorySync.PairFailure) e).pair)),
                                                    why, SyncStatus.ERROR);
                                        else if (!(cause instanceof UnknownHostException))
                                            status.setError(why);
                                        LOG.log(Level.WARNING, cause, cause::getMessage);
                                    }, network, crypto, passPairs.size() == config.links.size());
                            // a pass that could not open a single link never reaches a folder,
                            // so the folders would keep showing the last pass's result
                            if (! ranClean && status.getError().isPresent())
                                stampPairs(peergosDir, config, passPairs, status.getError().get(), SyncStatus.ERROR);
                        } finally {
                            if (meteredWatch != null) {
                                synchronized (thisPass) {
                                    thisPass.set(false);
                                }
                                meteredWatch.cancel();
                            }
                        }
                        // a handover onto mobile data stops the pass: the folders that do allow
                        // mobile carry on without the others, rather than all of them stalling
                        if (! status.getStopReason().filter(MOBILE_BLOCKED::equals).isPresent())
                            break;
                        reportMobileBlock(peergosDir, config, pairsBlockedOnMobile(config), passStart);
                        retryWhenUnmetered(context, peergosDir);
                        metered = true;
                        List<Integer> allowed = pairsAllowedOnMobile(config);
                        if (allowed.isEmpty() || allowed.equals(pairs))
                            break;
                        pairs = allowed;
                        status.resume();
                    }
                } finally {
                    // syncDirs only self-clears cancellation when it starts another pair, so a
                    // cancel during the last one would leave the shared status wedged.
                    status.resume();
                }
                // a pause is the user's choice, not a folder left behind
                boolean clean = status.isPaused() || ranClean;
                if (! clean)
                    retrySoon(context, peergosDir);
                return clean;
            } catch (MalformedURLException e) {
                e.printStackTrace();
                return true;
            } catch (Exception e) {
                Throwable cause = getCause(e);
                String msg = DirectorySync.describeError(cause);
                if (msg != null && ! msg.trim().isEmpty()) {
                    status.setError(msg);
                    // a pass can fail before it reaches any folder, and the reason each one
                    // last showed is then stale: it says mobile data when the server is down
                    if (syncConfig != null)
                        stampPairs(peergosDir, syncConfig, allPairs(syncConfig), msg, SyncStatus.ERROR);
                }
                LOG.log(Level.WARNING, cause, cause::getMessage);
                return false;
            }
        }
    }

    /** Wi-Fi can come back long before the next scheduled pass, so start one as soon as an
     *  unmetered network is up. */
    private static void retryWhenUnmetered(Context context, Path peergosDir) {
        enqueueRetry(context, peergosDir, "peergos-sync-unmetered", NetworkType.UNMETERED, 0);
    }

    /** A folder left needing attention is worth another go in a minute, rather than at the
     *  next scheduled pass a quarter of an hour away. */
    public static void retrySoon(Context context, Path peergosDir) {
        if (onScreenCadence.get())
            return;
        enqueueRetry(context, peergosDir, "peergos-sync-retry", NetworkType.CONNECTED, 60);
    }

    /** Unique work, so repeated failures replace each other rather than stack up, and it
     *  outlives this process. */
    private static void enqueueRetry(Context context, Path peergosDir, String name,
                                     NetworkType network, long delaySeconds) {
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(SyncWorker.class)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(network)
                                .build())
                        .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                        .setInputData(new Data.Builder()
                                .putString(PEERGOS_PATH, peergosDir.toString())
                                .build())
                        .build());
    }

    private static List<Integer> allPairs(SyncConfig config) {
        List<Integer> pairs = new ArrayList<>();
        for (int i = 0; i < config.links.size(); i++)
            pairs.add(i);
        return pairs;
    }

    private static List<Integer> pairsAllowedOnMobile(SyncConfig config) {
        List<Integer> pairs = new ArrayList<>();
        for (int i = 0; i < config.links.size(); i++)
            if (config.allowOnMobile.get(i))
                pairs.add(i);
        return pairs;
    }

    private static <T> List<T> pick(List<T> all, List<Integer> indices) {
        List<T> some = new ArrayList<>(indices.size());
        for (int i : indices)
            some.add(all.get(i));
        return some;
    }

    private static PairStatus pairStatus(Path peergosDir, SyncConfig config, int pair) {
        return new PairStatus(peergosDir,
                PairLogger.hash(config.remotePaths.get(pair), config.localDirs.get(pair)));
    }

    /** What each folder shows between passes: the pass itself only reports on a folder once
     *  it reaches it, which is too late to explain a wait, or a failure before the first one. */
    private static void stampPairs(Path peergosDir, SyncConfig config, List<Integer> pairs,
                                   String error, SyncStatus state) {
        for (int i : pairs) {
            PairStatus pair = pairStatus(peergosDir, config, i);
            pair.setError(error);
            pair.setStatus(state);
        }
    }

    /** A handover onto mobile data mid pass only holds up the folders this pass had not
     *  finished with. One it already synced is done, and turning it red for a network change
     *  that cost it nothing would send the user looking for a problem that is not there. */
    private static void reportMobileBlock(Path peergosDir, SyncConfig config, List<Integer> pairs,
                                          LocalDateTime passStart) {
        for (int i : pairs) {
            PairStatus pair = pairStatus(peergosDir, config, i);
            boolean doneThisPass = pair.getStatus() == SyncStatus.SYNCED
                    && pair.getError().isEmpty()
                    && pair.getTime().filter(t -> t.isAfter(passStart)).isPresent();
            if (doneThisPass)
                continue;
            pair.setError(MOBILE_BLOCKED);
            pair.setStatus(SyncStatus.ERROR);
        }
    }

    /** The folders mobile data is holding up. */
    private static List<Integer> pairsBlockedOnMobile(SyncConfig config) {
        List<Integer> blocked = new ArrayList<>();
        for (int i = 0; i < config.links.size(); i++)
            if (! config.allowOnMobile.get(i))
                blocked.add(i);
        return blocked;
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
