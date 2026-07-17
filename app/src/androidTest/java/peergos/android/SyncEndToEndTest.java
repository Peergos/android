package peergos.android;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import peergos.server.Main;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.JdbcTreeState;
import peergos.server.sync.PeergosSyncFS;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.corenode.CoreNode;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.LinkProperties;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.ThumbnailGenerator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
        ThumbnailGenerator.setInstance(new AndroidImageThumbnailer());
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
        String username = freshUsername();
        UserContext ctx = UserContext.signUp(username, "test-password", "", network, crypto).join();
        assertNotNull(ctx);
        assertEquals(username, ctx.username);
    }

    /** Push a directory of random-bytes "images" through DirectorySync to a
     *  fresh remote dir, then assert every file made it across.
     *  File count is configurable via the syncFileCount instrumentation arg
     *  (default 1000); size is uniform in [2 MiB, 5 MiB]. */
    @Test
    public void largeDirSyncToPeergos() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        int count = Integer.parseInt(args.getString("syncFileCount", "100"));
        long minSize = 2L * 1024 * 1024;
        long maxSize = 5L * 1024 * 1024;
        long seed = 42L;

        Context appCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Uri authorityUri = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        Bundle seedArgs = new Bundle();
        seedArgs.putInt("count", count);
        seedArgs.putLong("seed", seed);
        seedArgs.putLong("minSize", minSize);
        seedArgs.putLong("maxSize", maxSize);
        appCtx.getContentResolver().call(authorityUri,
                TestDocumentsProvider.METHOD_RESET_AND_SEED, null, seedArgs);

        String username = freshUsername();
        UserContext userCtx = UserContext.signUp(username, "test-password", "", network, crypto).join();
        String syncFolderName = "synced-images";
        userCtx.getUserRoot().join()
                .mkdir(syncFolderName, network, false, userCtx.mirrorBatId(), crypto).join();
        String peergosPath = "/" + username + "/" + syncFolderName;

        LinkProperties link = DirectorySync.init(userCtx, peergosPath);
        String cap = link.toLinkString(userCtx.signer.publicKeyHash);
        PeergosSyncFS remote = DirectorySync.buildRemote(cap, network, crypto);

        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID);
        AndroidSyncFileSystem local = new AndroidSyncFileSystem(treeUri, appCtx, crypto);

        java.nio.file.Path syncStateDir = Files.createTempDirectory(appCtx.getCacheDir().toPath(), "sync-state");
        JdbcTreeState state = new JdbcTreeState(":memory:");
        PublicKeyHash owner = network.coreNode.getPublicKeyHash(username).join().get();

        DirectorySync.syncDir(local, remote, false, false, owner, network, state,
                32, 5, syncStateDir, crypto, () -> false, DirectorySync::log);

        UserContext freshCtx = UserContext.signIn(username, "test-password", req -> {
            throw new IllegalStateException("MFA not expected");
        }, network, crypto).join();
        FileWrapper remoteDir = freshCtx.getByPath(peergosPath).join().get();
        Set<FileWrapper> children = remoteDir.getChildren(crypto.hasher, network).join();

        Set<String> remoteNames = new HashSet<>();
        for (FileWrapper c : children) remoteNames.add(c.getName());
        assertEquals(count, remoteNames.size());

        int withThumb = 0, withHash = 0;
        for (FileWrapper c : children) {
            FileProperties p = c.getFileProperties();
            System.out.println("CHILD " + c.getName() + " size=" + p.size
                    + " thumb=" + p.thumbnail.isPresent()
                    + " hash=" + p.treeHash.isPresent()
                    + " mime=" + p.mimeType);
            if (p.thumbnail.isPresent()) withThumb++;
            if (p.treeHash.isPresent()) withHash++;
        }
        FileWrapper sameCtx = userCtx.getByPath(peergosPath).join().get();
        for (FileWrapper c : sameCtx.getChildren(crypto.hasher, network).join()) {
            FileProperties p = c.getFileProperties();
            System.out.println("SAMECTX " + c.getName() + " hash=" + p.treeHash.isPresent());
        }
        assertTrue("expected thumbnails on synced images (got " + withThumb + "/" + count + ")",
                withThumb >= 1);
        assertTrue("expected tree hashes on synced files (got " + withHash + "/" + count + ")",
                withHash >= 1);
    }

    private static String freshUsername() {
        return "androidtest" + (System.currentTimeMillis() % 1_000_000);
    }
}
