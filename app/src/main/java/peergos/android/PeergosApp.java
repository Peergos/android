package peergos.android;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import peergos.server.Main;
import peergos.server.MountProperties;
import peergos.server.UserService;
import peergos.server.login.ConfigDeviceTokenStore;
import peergos.server.mount.MountBackend;
import peergos.server.net.MountConfigHandler;
import peergos.server.util.secrets.SecretStore;
import peergos.server.webdav.MountConfig;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.HttpPoster;

public class PeergosApp extends Application {

    private static final String TAG = "PeergosApp";
    private static final long MOUNT_RESTORE_DELAY_MS = 8_000;
    private static final AtomicBoolean mountRestored = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            bootstrapMount();
        } catch (Exception | LinkageError e) {
            // LinkageError as well: a core library method this platform lacks arrives as
            // one, and the drive provider failing to start is no reason to kill the app.
            // Not Throwable, which would swallow an out of memory error too
            Log.w(TAG, "DocumentsProvider mount bootstrap failed", e);
        }
    }

    private void bootstrapMount() throws Exception {
        File privateStorage = getFilesDir();
        Path peergosDir = Paths.get(privateStorage.getAbsolutePath());

        SecretStore store = new AndroidSecretStore(this);
        MountConfig persisted = MountConfigHandler.readConfig(peergosDir, store);
        String serverUrlStr = readSavedServerUrl(peergosDir).orElse("https://peergos.net");
        URL serverUrl = new URL(serverUrlStr);

        Supplier<Crypto> cryptoFactory = () -> Main.initCrypto(new ScryptAndroid());
        Supplier<NetworkAccess> networkFactory = () -> {
            HttpPoster poster = new AndroidPoster(serverUrl, true, Optional.empty(),
                    Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-android-mount"));
            Crypto c = cryptoFactory.get();
            ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, c.hasher);
            return NetworkAccess.buildViaPeergosInstance(poster, poster, localDht, 7_000, c.hasher, false,
                    new ConfigDeviceTokenStore(peergosDir.resolve("config"))).join();
        };

        MountProperties props = new MountProperties(persisted, peergosDir, serverUrlStr);
        MountBackend backend = new DocumentsProviderBackend(getApplicationContext());
        MountConfigHandler handler = new MountConfigHandler(props, store, backend, cryptoFactory, networkFactory);
        PeergosSession.setMountHandler(handler);
        // Restoring a mount signs in, which is a key derivation and a few round trips, and the
        // start of the app has a server to bring up and a page to load: the mount waits its
        // turn, unless something asks for it first.
        new Handler(Looper.getMainLooper()).postDelayed(PeergosApp::restoreMount, MOUNT_RESTORE_DELAY_MS);
    }

    /** The saved mount, signed in and enabled: once, whether its turn came or the documents
     *  provider was asked for roots before it. Nothing to do without a handler or a session
     *  already there. */
    static void restoreMount() {
        if (PeergosSession.context().isPresent() || ! mountRestored.compareAndSet(false, true))
            return;
        PeergosSession.mountHandler().ifPresent(MountConfigHandler::start);
    }

    static Optional<String> readSavedServerUrl(Path peergosDir) {
        try {
            Path configFile = peergosDir.resolve("config");
            if (!Files.exists(configFile))
                return Optional.empty();
            for (String line : Files.readAllLines(configFile)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("server-url") || trimmed.startsWith("peergos-url")) {
                    String[] parts = trimmed.split("=", 2);
                    if (parts.length == 2 && !parts[1].trim().isEmpty())
                        return Optional.of(parts[1].trim());
                }
            }
        } catch (IOException ignored) {}
        return Optional.empty();
    }
}
