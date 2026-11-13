package peergos.android;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.MessageDigest;
import java.security.SecureRandom;

import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.crypto.SigningKeyPair;
import peergos.shared.crypto.asymmetric.PublicSigningKey;
import peergos.shared.crypto.asymmetric.SigningDomain;
import peergos.shared.crypto.asymmetric.mldsa.Mldsa;
import peergos.shared.util.ArrayOps;

@RunWith(AndroidJUnit4.class)
public class MldsaTest {

    @Test
    public void publicKeyMatchesJdk() throws Exception {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++)
            seed[i] = (byte) i;
        byte[] pk = new BcMldsa().publicKey(seed);
        Assert.assertEquals(Mldsa.PUBLIC_KEY_BYTES, pk.length);
        // sha256 of the JDK's ML-DSA-44 public key for this seed
        Assert.assertEquals("9f107644c1084526af3bc8098680b05499a2325a644e388fb4f970e058d19d46",
                ArrayOps.bytesToHex(MessageDigest.getInstance("SHA-256").digest(pk)));
    }

    @Test
    public void appCryptoSignsWithHybridKeys() {
        Crypto crypto = Main.initCrypto(new ScryptAndroid(), new BcMldsa());
        Assert.assertTrue(crypto.mldsa instanceof BcMldsa);
        SigningKeyPair pair = SigningKeyPair.randomHybrid(crypto.random, crypto.signer, crypto.mldsa);
        Assert.assertEquals(PublicSigningKey.Type.HybridEd25519MLDSA, pair.publicSigningKey.type());
        byte[] message = "hello".getBytes();
        byte[] signed = pair.secretSigningKey.signMessage(message, SigningDomain.POINTER).join();
        Assert.assertArrayEquals(message, pair.publicSigningKey.unsignMessage(signed, SigningDomain.POINTER).join());
        signed[100] ^= 1;
        try {
            pair.publicSigningKey.unsignMessage(signed, SigningDomain.POINTER).join();
            Assert.fail("Tampered signature accepted");
        } catch (Throwable expected) {}
    }

    @Test
    public void benchmark() {
        BcMldsa m = new BcMldsa();
        byte[] seed = new byte[32], msg = new byte[100];
        new SecureRandom().nextBytes(seed);
        byte[] pk = m.publicKey(seed);
        byte[] sig = m.sign(msg, seed).join();
        Assert.assertTrue(m.verify(sig, msg, pk).join());
        time("keygen", () -> m.publicKey(seed));
        time("sign", () -> m.sign(msg, seed).join());
        time("verify", () -> m.verify(sig, msg, pk).join());
    }

    private static void time(String op, Runnable f) {
        long end = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < end)
            f.run();
        int n = 0;
        long t0 = System.nanoTime();
        while (System.nanoTime() - t0 < 4_000_000_000L) {
            f.run();
            n++;
        }
        Log.i("MldsaBench", String.format("bc %s: %.3f ms (%d ops)", op, (System.nanoTime() - t0) / 1e6 / n, n));
    }
}
