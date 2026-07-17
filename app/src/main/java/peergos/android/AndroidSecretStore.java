package peergos.android;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.util.Optional;

import peergos.server.util.secrets.SecretStore;

/** Android-backed {@link SecretStore}. Values are AES-GCM encrypted with a key
 *  held in the Android Keystore (hardware-backed on devices with a TEE).
 *
 *  <p>Must be constructed and passed explicitly to {@code MountConfigHandler} on
 *  Android — never call {@link SecretStore#detect()} from Android code paths,
 *  it'd pick the Linux JSON fallback (Android reports {@code os.name=Linux})
 *  and write the password in plaintext to app-private storage. */
public class AndroidSecretStore implements SecretStore {

    private final SharedPreferences prefs;

    public AndroidSecretStore(Context ctx) throws Exception {
        MasterKey master = new MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        this.prefs = EncryptedSharedPreferences.create(
                ctx, "peergos-secrets", master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    private static String key(String service, String account) {
        return service + "|" + account;
    }

    @Override
    public void put(String service, String account, String value) {
        prefs.edit().putString(key(service, account), value).apply();
    }

    @Override
    public Optional<String> get(String service, String account) {
        return Optional.ofNullable(prefs.getString(key(service, account), null));
    }

    @Override
    public void delete(String service, String account) {
        prefs.edit().remove(key(service, account)).apply();
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
