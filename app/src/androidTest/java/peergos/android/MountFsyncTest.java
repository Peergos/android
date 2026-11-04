package peergos.android;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.login.DeviceTokenStore;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.ThumbnailGenerator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** An app writing through the Files-app mount learns from fsync whether its bytes reached
 *  Peergos: they are there before it closes the file, and a failed upload is an I/O error
 *  rather than a success it will report to its user. Against the host server, as
 *  {@link SyncEndToEndTest}. */
@RunWith(AndroidJUnit4.class)
public class MountFsyncTest {

    private static Crypto crypto;
    private static NetworkAccess network;
    private static UserContext user;
    private static ContentResolver resolver;
    private static Stallable poster;

    /** The host server, until told to stall: then a peer that accepts a connection and neither reads nor
     *  answers, which is what a server or a network that stops part way looks like to a request. */
    private static final class Stallable implements HttpPoster {
        private final HttpPoster live;
        private final HttpPoster blackHole;
        private final ServerSocket sink;
        private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());
        volatile boolean stall;

        Stallable(HttpPoster live) throws IOException {
            this.live = live;
            // on either loopback: the device may bind one family and connect by the other
            sink = new ServerSocket(0);
            Thread accept = new Thread(() -> {
                try {
                    while (true) held.add(sink.accept());
                } catch (IOException closed) {
                }
            }, "black-hole");
            accept.setDaemon(true);
            accept.start();
            // by name: the app allows cleartext to localhost alone
            blackHole = new AndroidPoster(new URL("http://localhost:" + sink.getLocalPort() + "/"), false);
        }

        private HttpPoster target() {
            return stall ? blackHole : live;
        }

        @Override
        public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis) {
            return target().post(url, payload, unzip, timeoutMillis);
        }

        @Override
        public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
            return target().postUnzip(url, payload, timeoutMillis);
        }

        @Override
        public CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers) {
            return target().put(url, payload, headers);
        }

        @Override
        public CompletableFuture<byte[]> put(String url, byte[] payload, Map<String, String> headers, int timeoutMillis) {
            return target().put(url, payload, headers, timeoutMillis);
        }

        @Override
        public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
            return target().get(url, headers);
        }
    }

    @BeforeClass
    public static void signUp() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "10.0.2.2");
        int port = Integer.parseInt(args.getString("peergosPort", "8000"));
        URL serverUrl = new URL("http://" + host + ":" + port);

        crypto = Main.initCrypto(new ScryptAndroid());
        ThumbnailGenerator.setInstance(new AndroidImageThumbnailer());
        poster = new Stallable(new AndroidPoster(serverUrl, false,
                Optional.empty(), Optional.of("peergos-android-test")));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                5_000, crypto.hasher, false, DeviceTokenStore.inMemory()).join();
        String username = "mountfsync" + (System.currentTimeMillis() % 1_000_000);
        user = UserContext.signUp(username, "test-password", "", network, crypto).join();
        PeergosSession.publish(user, network, crypto);
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = ctx.getContentResolver();
    }

    @Test
    public void aSyncedWriteOfANewFileIsOnPeergosBeforeClose() throws Exception {
        String path = "/" + user.username + "/fsync-new.txt";
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("first part".getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            assertEquals("first part", contentOf(path));
            out.write(" and more".getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            assertEquals("first part and more", contentOf(path));
        }
        assertSettlesTo(path, "first part and more");
    }

    @Test
    public void aSyncedWriteBiggerThanAChunkGoesOnAfterItsSync() throws Exception {
        String path = "/" + user.username + "/fsync-big.bin";
        byte[] first = new byte[6 * 1024 * 1024];
        new Random(7).nextBytes(first);
        byte[] more = "the tail".getBytes(StandardCharsets.UTF_8);
        byte[] whole = Arrays.copyOf(first, first.length + more.length);
        System.arraycopy(more, 0, whole, first.length, more.length);
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write(first);
            out.getFD().sync();
            assertArrayEquals(first, bytesOf(path));
            out.write(more);
            out.getFD().sync();
            assertArrayEquals(whole, bytesOf(path));
        }
        assertSettlesTo(path, whole);
    }

    @Test
    public void aSyncedEditOfAnExistingFileIsOnPeergosBeforeClose() throws Exception {
        String path = "/" + user.username + "/fsync-edit.txt";
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("0123456789".getBytes(StandardCharsets.UTF_8));
        }
        assertSettlesTo(path, "0123456789");
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "rw");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("XY".getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            assertEquals("XY23456789", contentOf(path));
        }
        assertSettlesTo(path, "XY23456789");
    }

    @Test
    public void aSyncWhoseUploadFailsIsAnError() throws Exception {
        String folder = "fsync-gone";
        user.getUserRoot().join().mkdir(folder, network, false, user.mirrorBatId(), crypto).join();
        String path = "/" + user.username + "/" + folder + "/doomed.txt";
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("never stored".getBytes(StandardCharsets.UTF_8));
            // Where the bytes were going is gone before they are flushed
            user.getByPath("/" + user.username + "/" + folder).join().get()
                    .remove(user.getUserRoot().join(), Paths.get(user.username, folder), user).join();
            try {
                out.getFD().sync();
                fail("fsync reported the bytes safe, but nothing could store them");
            } catch (IOException expected) {
            }
        }
    }

    /** A failed sync ended the new file's upload, so a write after it is an error, not bytes nothing will store */
    @Test
    public void aWriteAfterAFailedSyncIsAnError() throws Exception {
        String folder = "fsync-after";
        user.getUserRoot().join().mkdir(folder, network, false, user.mirrorBatId(), crypto).join();
        String path = "/" + user.username + "/" + folder + "/after.txt";
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("first".getBytes(StandardCharsets.UTF_8));
            user.getByPath("/" + user.username + "/" + folder).join().get()
                    .remove(user.getUserRoot().join(), Paths.get(user.username, folder), user).join();
            try {
                out.getFD().sync();
                fail("fsync reported the bytes safe, but nothing could store them");
            } catch (IOException expected) {
            }
            try {
                out.write("more".getBytes(StandardCharsets.UTF_8));
                fail("a write after the failed sync was taken");
            } catch (IOException expected) {
            }
        }
    }

    /** A failed sync of an edited file sticks as a new file's does: the writer was told its bytes aren't safe */
    @Test
    public void aFailedSyncOfAnEditSticks() throws Exception {
        String folder = "fsync-edit-gone";
        user.getUserRoot().join().mkdir(folder, network, false, user.mirrorBatId(), crypto).join();
        String path = "/" + user.username + "/" + folder + "/edited.txt";
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("0123456789".getBytes(StandardCharsets.UTF_8));
        }
        assertSettlesTo(path, "0123456789");
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "rw");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("XY".getBytes(StandardCharsets.UTF_8));
            user.getByPath("/" + user.username + "/" + folder).join().get()
                    .remove(user.getUserRoot().join(), Paths.get(user.username, folder), user).join();
            try {
                out.getFD().sync();
                fail("fsync reported the edit safe, but nothing could store it");
            } catch (IOException expected) {
            }
            try {
                out.write("more".getBytes(StandardCharsets.UTF_8));
                fail("a write after the failed sync was taken");
            } catch (IOException expected) {
            }
        }
    }

    /** The server stops taking part while a file is open: the sync is an error once the request behind it has
     *  timed out and its retries are spent, which is minutes, and it sticks, as any failed sync does. The first
     *  request of an upload is small, so this is the wait a read timeout ends; the one a body nobody takes makes,
     *  which only the request's deadline ends, is AndroidPosterTimeoutTest's. */
    @Test
    public void aSyncToAServerThatStopsAnsweringIsAnErrorInTime() throws Exception {
        String folder = "fsync-stall";
        user.getUserRoot().join().mkdir(folder, network, false, user.mirrorBatId(), crypto).join();
        String path = "/" + user.username + "/" + folder + "/stalled.txt";
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "w");
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write("never taken".getBytes(StandardCharsets.UTF_8));
            poster.stall = true;
            long started = System.currentTimeMillis();
            try {
                out.getFD().sync();
                fail("fsync reported the bytes safe through a server that takes nothing");
            } catch (IOException expected) {
            }
            long took = System.currentTimeMillis() - started;
            assertTrue("the sync never reached the server that takes nothing", ! poster.held.isEmpty());
            assertTrue("fsync failed after " + took + " ms, before any request could have timed out", took >= 10_000);
            assertTrue("fsync waited " + took + " ms on a server that takes nothing", took < 6 * 60_000);
            try {
                out.write("more".getBytes(StandardCharsets.UTF_8));
                fail("a write after the failed sync was taken");
            } catch (IOException expected) {
            }
        } finally {
            poster.stall = false;
        }
    }

    /** As the share target writes what is shared with Peergos: a stream from openOutputStream, synced before it is
     *  closed. */
    @Test
    public void aStreamFromOpenOutputStreamHearsAFailedUpload() throws Exception {
        String folder = "fsync-share";
        user.getUserRoot().join().mkdir(folder, network, false, user.mirrorBatId(), crypto).join();
        String path = "/" + user.username + "/" + folder + "/shared.txt";
        try (OutputStream out = resolver.openOutputStream(document(path))) {
            assertTrue("the mount's stream cannot be synced: " + out.getClass(), out instanceof FileOutputStream);
            out.write("shared".getBytes(StandardCharsets.UTF_8));
            user.getByPath("/" + user.username + "/" + folder).join().get()
                    .remove(user.getUserRoot().join(), Paths.get(user.username, folder), user).join();
            try {
                ((FileOutputStream) out).getFD().sync();
                fail("the share would have been reported as saved");
            } catch (IOException expected) {
            }
        }
    }

    private static Uri document(String path) {
        return DocumentsContract.buildDocumentUri(DocumentsProviderBackend.AUTHORITY, path);
    }

    /** The file as a fresh look at Peergos finds it. */
    private static byte[] bytesOf(String path) throws Exception {
        FileWrapper file = user.getByPath(path).join()
                .orElseThrow(() -> new AssertionError("Not on Peergos: " + path));
        int size = (int) file.getSize();
        byte[] bytes = new byte[size];
        try (AsyncReader reader = file.getInputStream(network, crypto, size, p -> {}).join()) {
            int read = 0;
            while (read < size) {
                int n = reader.readIntoArray(bytes, read, size - read).join();
                if (n <= 0) break;
                read += n;
            }
        }
        return bytes;
    }

    private static String contentOf(String path) throws Exception {
        return new String(bytesOf(path), StandardCharsets.UTF_8);
    }

    /** The close uploads in the background. */
    private static void assertSettlesTo(String path, byte[] expected) throws Exception {
        byte[] now = new byte[0];
        for (int i = 0; i < 60; i++) {
            now = bytesOf(path);
            if (Arrays.equals(now, expected)) return;
            Thread.sleep(500);
        }
        assertArrayEquals(expected, now);
    }

    private static void assertSettlesTo(String path, String expected) throws Exception {
        assertSettlesTo(path, expected.getBytes(StandardCharsets.UTF_8));
    }
}
