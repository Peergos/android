package peergos.android;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import peergos.server.crypto.hash.ScryptJava;
import peergos.shared.user.SecretGenerationAlgorithm;
import peergos.shared.user.fs.EncryptedCapability;
import peergos.shared.util.ArrayOps;
import peergos.shared.util.Futures;

public class CachingHasher extends ScryptJava {

    private final Map<String, byte[]> hashCache = new HashMap<>();
    private final File cache;

    public CachingHasher(File cache) {
        this.cache = cache;
        if (cache.exists()) {
            try {
                List<String> lines = Files.readAllLines(cache.toPath());
                for (String line : lines) {
                    hashCache.put(line.split(" ")[0], ArrayOps.hexToBytes(line.split(" ")[1]));
                }
            } catch (IOException e) {}
        }
    }

    private void saveToFile() {
        String txt = hashCache.entrySet().stream().map(e -> e.getKey() + " " + ArrayOps.bytesToHex(e.getValue())).collect(Collectors.joining("\n"));
        try {
            Files.write(cache.toPath(), txt.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String label, String linkPassword, SecretGenerationAlgorithm algorithm) {
        boolean isLink = algorithm.equals(EncryptedCapability.LINK_KEY_GENERATOR);
        if (isLink) {
            byte[] cached = hashCache.get(label + "_" + linkPassword);
            if (cached!= null)
                return Futures.of(cached);
        }
        return super.hashToKeyBytes(label, linkPassword, algorithm)
                .thenApply(res -> {
                    hashCache.put(label + "_" + linkPassword, res);
                    saveToFile();
                    return res;
                });
    }
}
