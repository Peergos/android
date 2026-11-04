package peergos.android;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import peergos.server.Main;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.JdbcTreeState;
import peergos.server.sync.LocalFileSystem;
import peergos.server.sync.SyncFilesystem;
import peergos.server.sync.SyncState;
import peergos.server.sync.UnrepresentableNameException;
import peergos.shared.Crypto;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.ResumeUploadProps;

/** A file on the device is found from its path with one provider query, as a document that DocumentFile can't
 *  rename, so every rename the sync made on the device failed: following a rename on the other side, and keeping
 *  the device's version of a file changed on both sides under a new name.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidRenameTest {

    private Context ctx;
    private Crypto crypto;
    private AndroidSyncFileSystem device;
    private LocalFileSystem other;
    private Path otherRoot;

    @Before
    public void setUp() throws IOException {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        crypto = Main.initCrypto(new ScryptAndroid());
        Bundle seed = new Bundle();
        seed.putInt("count", 0);
        ctx.getContentResolver().call(Uri.parse("content://" + TestDocumentsProvider.AUTHORITY),
                TestDocumentsProvider.METHOD_RESET_AND_SEED, null, seed);
        device = new AndroidSyncFileSystem(DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY,
                TestDocumentsProvider.ROOT_ID), ctx, crypto);
        otherRoot = Files.createTempDirectory(ctx.getCacheDir().toPath(), "sync-other");
        other = new LocalFileSystem(otherRoot, crypto.hasher);
    }

    private void write(String path, String contents) throws IOException {
        byte[] data = contents.getBytes();
        device.setBytes(Paths.get(path), 0, AsyncReader.build(data), data.length, Optional.empty(), Optional.empty(),
                Optional.empty(), ResumeUploadProps.random(crypto), () -> false, x -> {});
    }

    private void sync(SyncState state) throws Exception {
        DirectorySync.syncDir(device, other, true, true, null, null, state, 32, 5,
                Files.createTempDirectory(ctx.getCacheDir().toPath(), "sync-state"), crypto, () -> false, DirectorySync::log);
    }

    private static Map<String, String> tree(SyncFilesystem fs) throws IOException {
        Map<String, String> res = new TreeMap<>();
        fs.applyToSubtree(f -> {
            try (AsyncReader in = fs.getBytes(fs.resolve(f.relPath), 0)) {
                byte[] data = new byte[(int) f.size];
                for (int done = 0; done < data.length; )
                    done += in.readIntoArray(data, done, data.length - done).join();
                res.put(f.relPath, new String(data));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, d -> res.put(d.relPath, "dir"), false);
        return res;
    }

    @Test
    public void renameOfAFile() throws Exception {
        write("d/a", "contents");
        device.moveTo(Paths.get("d/a"), Paths.get("d/b"));
        Assert.assertEquals(Map.of("d", "dir", "d/b", "contents"), tree(device));
    }

    /** A move to the top of the synced folder made its parent folder, which there is null */
    @Test
    public void moveIntoTheTopFolder() throws Exception {
        write("d/a", "contents");
        device.moveTo(Paths.get("d/a"), Paths.get("a"));
        Assert.assertEquals(Map.of("d", "dir", "a", "contents"), tree(device));
    }

    @Test
    public void renameOnTheOtherSideIsFollowed() throws Exception {
        SyncState state = new JdbcTreeState(":memory:");
        write("a", "contents");
        sync(state);
        Files.move(otherRoot.resolve("a"), otherRoot.resolve("b"));
        sync(state);
        Assert.assertEquals(Map.of("b", "contents"), tree(device));
    }

    /** The provider numbers a name that differs from the file's own only in case, as storage that ignores case has it
     *  taken, by the file itself */
    @Test
    public void aChangeOfCaseOnTheOtherSideIsFollowed() throws Exception {
        SyncState state = new JdbcTreeState(":memory:");
        write("photo.jpg", "contents");
        sync(state);
        Files.move(otherRoot.resolve("photo.jpg"), otherRoot.resolve("Photo.jpg"));
        sync(state);
        Assert.assertEquals(Map.of("Photo.jpg", "contents"), tree(device));
        sync(state);
        Assert.assertEquals(Map.of("Photo.jpg", "contents"), tree(device));
        Assert.assertEquals(Map.of("Photo.jpg", "contents"), tree(other));
    }

    private void failRenamesTo(String name) {
        ctx.getContentResolver().call(Uri.parse("content://" + TestDocumentsProvider.AUTHORITY),
                TestDocumentsProvider.METHOD_FAIL_RENAMES_TO, name, null);
    }

    /** A change of case goes by way of another name, so a second rename that fails puts the file back: left under the
     *  name in between, a file deleted there before the next pass would be synced back under it */
    @Test
    public void aChangeOfCaseThatFailsHalfwayIsPutBack() throws Exception {
        SyncState state = new JdbcTreeState(":memory:");
        write("photo.jpg", "contents");
        sync(state);
        Files.move(otherRoot.resolve("photo.jpg"), otherRoot.resolve("Photo.jpg"));
        failRenamesTo("Photo.jpg");
        try {
            sync(state);
            Assert.fail("The rename did not fail");
        } catch (RuntimeException expected) {}
        Assert.assertEquals(Map.of("photo.jpg", "contents"), tree(device));

        failRenamesTo(null);
        Files.delete(otherRoot.resolve("Photo.jpg"));
        sync(state);
        Assert.assertEquals(Map.of(), tree(device));
        Assert.assertEquals(Map.of(), tree(other));
    }

    /** A rename the storage can't hold is put back, and when that fails too, the error says where the file is */
    @Test
    public void aRenameTheStorageCannotHoldThatCannotBePutBack() throws Exception {
        write("x.txt", "contents");
        failRenamesTo("x.txt");
        try {
            device.moveTo(Paths.get("x.txt"), Paths.get("a:b.txt"));
            Assert.fail("The rename was taken for one");
        } catch (UncheckedIOException e) {
            Assert.assertTrue(e.toString(), e.getCause() instanceof UnrepresentableNameException);
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("it is now a_b.txt"));
        }
        Assert.assertEquals(Map.of("a_b.txt", "contents"), tree(device));
    }

    @Test
    public void changeOnBothSidesKeepsBoth() throws Exception {
        SyncState state = new JdbcTreeState(":memory:");
        write("f", "original");
        sync(state);
        Files.write(otherRoot.resolve("f"), "changed there".getBytes());
        Files.setLastModifiedTime(otherRoot.resolve("f"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        device.delete(Paths.get("f"));
        write("f", "changed here");
        sync(state);
        Map<String, String> synced = tree(device);
        Assert.assertEquals(synced, tree(other));
        Assert.assertTrue(synced.toString(), synced.values().containsAll(java.util.List.of("changed there", "changed here")));
    }
}
