package peergos.android;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import peergos.server.Main;
import peergos.server.ServerProcesses;
import peergos.server.util.Args;

import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class SyncEndToEndTest {

    private static ServerProcesses processes;
    private static Args args;
    private static Path peergosDir;

    @BeforeClass
    public static void startServer() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        peergosDir = new File(ctx.getCacheDir(), "peergos-e2e-" + System.currentTimeMillis()).toPath();
        Files.createDirectories(peergosDir);

        int port = freePort();
        int proxyPort = freePort();
        int gatewayPort = freePort();
        int ipfsApi = freePort();
        int ipfsGw = freePort();
        int ipfsSwarm = freePort();
        int metricsPort = freePort();

        args = Args.parse(new String[]{
                "-port", Integer.toString(port),
                "-proxy-target", "/ip4/127.0.0.1/tcp/" + proxyPort,
                "-gateway-port", Integer.toString(gatewayPort),
                "-ipfs-api-address", "/ip4/127.0.0.1/tcp/" + ipfsApi,
                "-ipfs-gateway-address", "/ip4/127.0.0.1/tcp/" + ipfsGw,
                "-ipfs-swarm-port", Integer.toString(ipfsSwarm),
                "-ipfs.metrics.port", Integer.toString(metricsPort),
                "-useIPFS", "false",
                "-listen-host", "localhost",
                "-admin-usernames", "peergos",
                "-logToConsole", "true",
                "-enable-gc", "false",
                "max-users", "10000",
                "max-daily-signups", "20000",
                Main.PEERGOS_PATH, peergosDir.toString(),
                "peergos.password", "testpassword",
                "pki.keygen.password", "testpkipassword",
                "pki.keyfile.password", "testpassword",
        });

        processes = Main.PKI_INIT.main(args);
        assertNotNull("PKI_INIT returned null", processes);
        assertNotNull("localApi missing", processes.localApi);
    }

    @AfterClass
    public static void stopServer() {
        if (processes != null && processes.localApi != null) {
            try { processes.localApi.stop(); } catch (Exception ignored) {}
        }
        if (processes != null && processes.p2pApi != null && processes.p2pApi != processes.localApi) {
            try { processes.p2pApi.stop(); } catch (Exception ignored) {}
        }
    }

    @Test
    public void serverIsAlive() {
        assertNotNull("storage id was null", processes.localApi.storage.id().join());
    }

    private static final Set<Integer> handedOut = new HashSet<>();

    private static synchronized int freePort() throws IOException {
        for (int i = 0; i < 50; i++) {
            int port;
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress((InetAddress) null, 0));
                port = s.getLocalPort();
            }
            if (handedOut.add(port))
                return port;
        }
        throw new IOException("could not allocate free port");
    }
}
