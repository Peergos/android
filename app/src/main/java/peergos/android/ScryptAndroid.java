package peergos.android;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import peergos.server.crypto.hash.ScryptJava;
import peergos.shared.crypto.hash.Hash;
import peergos.shared.user.ScryptGenerator;
import peergos.shared.user.SecretGenerationAlgorithm;

public class ScryptAndroid extends ScryptJava {
    private static final Logger LOG = Logger.getGlobal();

    static {
        System.loadLibrary("scrypt");
        System.out.println("Loaded native scrypt!");

    }

    native void crypto_scrypt(byte[] pass, int passLen, byte[] salt, int saltLen, int mem, int cpu, int p, byte[] outBytes, int outLen);

    @Override
    public CompletableFuture<byte[]> hashToKeyBytes(String username, String password, SecretGenerationAlgorithm algorithm) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        if (algorithm.getType() == SecretGenerationAlgorithm.Type.Scrypt) {
            byte[] hash = Hash.sha256(password.getBytes());
            byte[] salt = username.getBytes();
            ScryptGenerator params = (ScryptGenerator) algorithm;
            long t1 = System.currentTimeMillis();
            int parallelism = params.parallelism;
            int nOutputBytes = params.outputBytes;
            int cpuCost = params.cpuCost;
            int memoryCost = 1 << params.memoryCost; // Amount of ram required to run algorithm in bytes
            byte[] scryptHash = new byte[nOutputBytes];
            crypto_scrypt(hash, hash.length, salt, salt.length, memoryCost, cpuCost, parallelism, scryptHash, scryptHash.length);
            long t2 = System.currentTimeMillis();
            LOG.info("Scrypt native hashing took: " + (t2 - t1) + " mS");
            res.complete(scryptHash);
            return res;
        }
        throw new IllegalStateException("Unknown user generation algorithm: " + algorithm);
    }
}
