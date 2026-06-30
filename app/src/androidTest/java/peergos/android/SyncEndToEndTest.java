package peergos.android;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.util.Collections;
import java.util.Optional;

import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.UserContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** End-to-end sync test against a Peergos server running on the host.
 *  The emulator reaches the host via 10.0.2.2; the server itself runs on a
 *  normal JVM so we sidestep Android-incompatible bits of peergos.server
 *  (java.net.http, sun.net.httpserver, sqlite-jdbc Linux natives).
 *  Host/port come from instrumentation args (set by the workflow). */
@RunWith(AndroidJUnit4.class)
public class SyncEndToEndTest {

    private static Crypto crypto;
    private static NetworkAccess network;

    @BeforeClass
    public static void connectToHostServer() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "10.0.2.2");
        int port = Integer.parseInt(args.getString("peergosPort", "8000"));
        URL serverUrl = new URL("http://" + host + ":" + port);

        crypto = Main.initCrypto(new ScryptAndroid());
        HttpPoster poster = new AndroidPoster(serverUrl, false,
                Optional.empty(), Optional.of("peergos-android-test"));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        CoreNode core = NetworkAccess.buildDirectCorenode(poster);
        network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                5_000, crypto.hasher, false).join();
    }

    @Test
    public void serverIsReachable() {
        assertNotNull(network.dhtClient.id().join());
    }

    @Test
    public void signUpRoundTrip() {
        String username = "androidtest" + (System.currentTimeMillis() % 1_000_000);
        UserContext ctx = UserContext.signUp(username, "test-password", "", network, crypto).join();
        assertNotNull(ctx);
        assertEquals(username, ctx.username);
    }
}
