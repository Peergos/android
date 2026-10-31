package peergos.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.util.Optional;

import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.login.DeviceTokenStore;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.UserContext;

/** A request reaching the Files-app mount while a saved mount is still signing in, as one does when the process
 *  was started for it. It was answered "not found" a second or two before the document was there. Against the host
 *  server, as {@link SyncEndToEndTest}. */
@RunWith(AndroidJUnit4.class)
public class MountColdStartTest {

    private static Crypto crypto;
    private static NetworkAccess network;
    private static UserContext user;

    @BeforeClass
    public static void signUp() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "10.0.2.2");
        int port = Integer.parseInt(args.getString("peergosPort", "8000"));
        crypto = Main.initCrypto(new ScryptAndroid());
        HttpPoster poster = new AndroidPoster(new URL("http://" + host + ":" + port), false,
                Optional.empty(), Optional.of("peergos-android-test"));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                5_000, crypto.hasher, false, DeviceTokenStore.inMemory()).join();
        // a device with a saved mount signs it in as the process starts, and two sign-ins at once can fail
        // inside the crypto
        PeergosSession.awaitContext(PeergosSession.SIGN_IN_WAIT_MILLIS);
        user = UserContext.signUp("coldstart" + (System.currentTimeMillis() % 1_000_000), "test-password", "",
                network, crypto).join();
    }

    @Test
    public void aRequestWaitsForTheSignIn() throws Exception {
        PeergosSession.clear();
        PeergosSession.signingIn();
        new Thread(() -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                return;
            }
            PeergosSession.publish(user, network, crypto);
        }).start();
        long start = System.currentTimeMillis();
        try (Cursor c = query("/" + user.username)) {
            Assert.assertNotNull("the user's root was not found while the sign-in was under way", c);
            Assert.assertTrue(c.moveToFirst());
        }
        Assert.assertTrue("answered before the sign-in had published", System.currentTimeMillis() - start >= 2_500);
    }

    @Test
    public void aSignInThatNeverCameIsWaitedForOnce() throws Exception {
        PeergosSession.clear();
        PeergosSession.signingIn();
        long start = System.currentTimeMillis();
        try (Cursor c = query("/" + user.username)) {
            Assert.assertNull(c);
        }
        long waited = System.currentTimeMillis() - start;
        Assert.assertTrue("gave up after " + waited + " ms", waited >= 15_000);
        start = System.currentTimeMillis();
        try (Cursor c = query("/" + user.username)) {
            Assert.assertNull(c);
        }
        Assert.assertTrue("waited again", System.currentTimeMillis() - start < 2_000);
        PeergosSession.publish(user, network, crypto);
    }

    @Test
    public void aSignInThatWillNotHappenIsNotWaitedFor() throws Exception {
        PeergosSession.clear();
        PeergosSession.signingIn();
        // as the app says when the saved mount's setup fails at start
        PeergosSession.notSigningIn();
        long start = System.currentTimeMillis();
        try (Cursor c = query("/" + user.username)) {
            Assert.assertNull(c);
        }
        Assert.assertTrue("waited for a sign-in that was not coming", System.currentTimeMillis() - start < 2_000);
        PeergosSession.publish(user, network, crypto);
    }

    private static Cursor query(String documentId) {
        ContentResolver resolver = InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
        Uri doc = DocumentsContract.buildDocumentUri(DocumentsProviderBackend.AUTHORITY, documentId);
        return resolver.query(doc, null, null, null, null);
    }
}
