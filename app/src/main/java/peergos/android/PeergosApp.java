package peergos.android;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
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
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.HttpPoster;

public class PeergosApp extends Application {

    private static final String TAG = "PeergosApp";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Here rather than in onCreate: the drive provider is installed between the two, and a request the process
        // was started for can reach it while onCreate is still running.
        if (savedMountEnabled(Paths.get(getFilesDir().getAbsolutePath())))
            PeergosSession.signingIn();
    }

    /** Whether a mount was left on, read from its file without the secrets that knowing this does not need. */
    @SuppressWarnings("unchecked")
    static boolean savedMountEnabled(Path peergosDir) {
        Path file = peergosDir.resolve(MountConfig.FILENAME);
        if (! Files.exists(file))
            return false;
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return MountConfig.fromJson((Map<String, Object>) JSONParser.parse(json)).enabled;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

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
            // the saved mount will not sign in, so a request waiting for it is answered now
            PeergosSession.notSigningIn();
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
        handler.start();
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
