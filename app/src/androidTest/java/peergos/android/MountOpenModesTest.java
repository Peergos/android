package peergos.android;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

import static org.junit.Assert.assertEquals;

/** Android checks an app's write grant only when the mode it opens a document with has a 'w'
 *  in it, so the Files-app mount writes for no other mode: an app holding a read-only grant
 *  could otherwise change or empty the file. Against the host server, as
 *  {@link SyncEndToEndTest}. */
@RunWith(AndroidJUnit4.class)
public class MountOpenModesTest {

    private static Crypto crypto;
    private static NetworkAccess network;
    private static UserContext user;
    private static ContentResolver resolver;

    @BeforeClass
    public static void signUp() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "10.0.2.2");
        int port = Integer.parseInt(args.getString("peergosPort", "8000"));
        URL serverUrl = new URL("http://" + host + ":" + port);

        crypto = Main.initCrypto(new ScryptAndroid());
        ThumbnailGenerator.setInstance(new AndroidImageThumbnailer());
        HttpPoster poster = new AndroidPoster(serverUrl, false,
                Optional.empty(), Optional.of("peergos-android-test"));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                5_000, crypto.hasher, false, DeviceTokenStore.inMemory()).join();
        String username = "mountmodes" + (System.currentTimeMillis() % 1_000_000);
        user = UserContext.signUp(username, "test-password", "", network, crypto).join();
        PeergosSession.publish(user, network, crypto);
        resolver = InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
    }

    @Test
    public void aModeWithoutAWCannotWrite() throws Exception {
        String path = "/" + user.username + "/modes-kept.txt";
        write(path, "w", "keep me");
        assertSettlesTo(path, "keep me");
        List<String> opened = new ArrayList<>();
        for (String mode : new String[] {"a", "at"}) {
            try {
                resolver.openFileDescriptor(document(path), mode).close();
                opened.add(mode);
            } catch (IllegalArgumentException expected) {
            }
        }
        assertEquals("opened for writing although Android checks these only against a read grant;"
                + " the file now holds \"" + contentOf(path) + "\"", List.of(), opened);
        assertEquals("keep me", contentOf(path));
    }

    @Test
    public void anAppendWithAWAddsToTheEnd() throws Exception {
        String path = "/" + user.username + "/modes-append.txt";
        write(path, "w", "start");
        assertSettlesTo(path, "start");
        write(path, "wa", " and end");
        assertSettlesTo(path, "start and end");
    }

    @Test
    public void anEditInPlaceKeepsTheRest() throws Exception {
        String path = "/" + user.username + "/modes-rw.txt";
        write(path, "w", "0123456789");
        assertSettlesTo(path, "0123456789");
        // read back through "rw" and close without writing: nothing is lost
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "rw");
             FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            assertEquals("0123456789", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertSettlesTo(path, "0123456789");
        // one byte in the middle: the rest stays
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "rw");
             FileChannel out = new FileOutputStream(pfd.getFileDescriptor()).getChannel()) {
            out.position(4);
            out.write(ByteBuffer.wrap("X".getBytes(StandardCharsets.UTF_8)));
        }
        assertSettlesTo(path, "0123X56789");
    }

    @Test
    public void aTruncatingOpenEmptiesTheFile() throws Exception {
        String path = "/" + user.username + "/modes-wt.txt";
        write(path, "w", "something long");
        assertSettlesTo(path, "something long");
        write(path, "wt", "short");
        assertSettlesTo(path, "short");
    }

    /** As a writer that treats an append fd as one: it seeks to the end first, and the write
     *  must land there, not at twice the size. */
    @Test
    public void anAppendAfterSeekingToTheEndLandsAtTheEnd() throws Exception {
        String path = "/" + user.username + "/modes-seek.txt";
        write(path, "w", "start");
        assertSettlesTo(path, "start");
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), "wa");
             FileChannel out = new FileOutputStream(pfd.getFileDescriptor()).getChannel()) {
            out.position(out.size());
            out.write(ByteBuffer.wrap(" and end".getBytes(StandardCharsets.UTF_8)));
        }
        assertSettlesTo(path, "start and end");
    }

    private static void write(String path, String mode, String text) throws IOException {
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document(path), mode);
             FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Uri document(String path) {
        return DocumentsContract.buildDocumentUri(DocumentsProviderBackend.AUTHORITY, path);
    }

    /** The file as a fresh look at Peergos finds it. */
    private static String contentOf(String path) throws Exception {
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
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The close uploads in the background. */
    private static void assertSettlesTo(String path, String expected) throws Exception {
        String now = "";
        for (int i = 0; i < 60; i++) {
            now = contentOf(path);
            if (now.equals(expected)) return;
            Thread.sleep(500);
        }
        assertEquals(expected, now);
    }
}
