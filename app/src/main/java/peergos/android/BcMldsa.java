package peergos.android;

import org.bouncycastle.crypto.params.MLDSAParameters;
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters;
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.MLDSASigner;

import java.security.SecureRandom;
import java.util.concurrent.CompletableFuture;

import peergos.shared.crypto.asymmetric.mldsa.Mldsa;
import peergos.shared.util.Futures;

/** ML-DSA-44 from Bouncy Castle's lightweight API, for Android, whose platform providers have no ML-DSA. */
public class BcMldsa implements Mldsa {
    private final SecureRandom random = new SecureRandom();

    private static MLDSAPrivateKeyParameters secret(byte[] seed) {
        if (seed.length != SEED_BYTES)
            throw new IllegalStateException("Invalid ML-DSA-44 seed size: " + seed.length);
        return new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_44, seed);
    }

    @Override
    public byte[] publicKey(byte[] seed) {
        return secret(seed).getPublicKey();
    }

    @Override
    public CompletableFuture<byte[]> sign(byte[] message, byte[] seed) {
        try {
            MLDSASigner signer = new MLDSASigner();
            signer.init(true, new ParametersWithRandom(secret(seed), random));
            signer.update(message, 0, message.length);
            return Futures.of(signer.generateSignature());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public CompletableFuture<Boolean> verify(byte[] signature, byte[] message, byte[] publicKey) {
        if (signature.length != SIGNATURE_BYTES || publicKey.length != PUBLIC_KEY_BYTES)
            return Futures.of(false);
        MLDSASigner verifier = new MLDSASigner();
        verifier.init(false, new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_44, publicKey));
        verifier.update(message, 0, message.length);
        return Futures.of(verifier.verifySignature(signature));
    }
}
