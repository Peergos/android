package peergos.android;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

import peergos.server.Main;
import peergos.server.MountProperties;
import peergos.server.UserService;
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

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            bootstrapMount();
        } catch (Exception e) {
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
            return NetworkAccess.buildViaPeergosInstance(poster, poster, localDht, 7_000, c.hasher, false).join();
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
