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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import peergos.server.Main;
import peergos.server.sync.CopyOp;
import peergos.server.sync.DirectorySync;
import peergos.server.sync.FileState;
import peergos.server.sync.JdbcTreeState;
import peergos.server.sync.LocalFileSystem;
import peergos.server.sync.SyncFilesystem;
import peergos.server.sync.SyncState;
import peergos.shared.Crypto;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.ResumeUploadProps;

/**
 * A download to the device is written into a folder of its own and moved into place once complete, so a part written
 * by an interrupted download is never moved with its folder, or synced as a file of its own. This syncs a
 * TestDocumentsProvider tree, through the same SAF calls as a real one, with a plain folder in the app's cache.
 */
@RunWith(AndroidJUnit4.class)
public class AndroidPartialCopyTest {

    private Context ctx;
    private Crypto crypto;
    private AndroidSyncFileSystem device;
    private LocalFileSystem other;
    private Path otherRoot;

    private void reset(boolean moveSupported) throws IOException {
        reset(moveSupported, null);
    }

    private void reset(boolean moveSupported, String providerLocale) throws IOException {
        Uri authorityUri = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        Bundle seed = new Bundle();
        seed.putInt("count", 0);
        seed.putLong("seed", 1);
        seed.putLong("minSize", 0);
        seed.putLong("maxSize", 0);
        seed.putBoolean("moveSupported", moveSupported);
        seed.putString("locale", providerLocale);
        ctx.getContentResolver().call(authorityUri, TestDocumentsProvider.METHOD_RESET_AND_SEED, null, seed);
        Uri treeUri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID);
        device = new AndroidSyncFileSystem(treeUri, ctx, crypto);
        otherRoot = Files.createTempDirectory(ctx.getCacheDir().toPath(), "sync-other");
        other = new LocalFileSystem(otherRoot, crypto.hasher);
    }

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        crypto = Main.initCrypto(new ScryptAndroid());
    }

    private void sync(SyncState state) throws Exception {
        DirectorySync.syncDir(device, other, true, true, null, null, state, 32, 5,
                Files.createTempDirectory(ctx.getCacheDir().toPath(), "sync-state"), crypto, () -> false, DirectorySync::log);
    }

    private static byte[] randomText(int size, int seed) {
        Random r = new Random(seed);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
            data[i] = (byte) ('a' + r.nextInt(26));
        return data;
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

    /** Copies a change made on the other side to the device, as a sync would, until isCancelled says to stop */
    private void download(SyncState state, String relPath, byte[] changed, Supplier<Boolean> isCancelled) throws Exception {
        Path rel = Paths.get(relPath);
        Files.createDirectories(otherRoot.resolve(rel).getParent());
        Files.write(otherRoot.resolve(rel), changed);
        Files.setLastModifiedTime(otherRoot.resolve(rel), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() / 1000 * 1000 + 5_000));
        FileState synced = state.byPath(relPath);
        int chunkSize = synced != null ? synced.chunkSize : Chunk.LEGACY_SIZE;
        HashTree hash = other.hashFile(rel, Optional.empty(), relPath, state, changed.length, chunkSize);
        FileState source = new FileState(relPath, other.getLastModified(rel), changed.length, hash, chunkSize);
        FileState target = device.exists(rel) ? synced : null;
        List<CopyOp> ops = source.diffRanges(target).stream()
                .map(d -> new CopyOp(true, other.resolve(relPath), device.resolve(relPath), source, target, d.left, d.right,
                        ResumeUploadProps.random(crypto)))
                .collect(Collectors.toList());
        DirectorySync.copyFileDiffAndTruncate(other, device, ops, state, isCancelled, DirectorySync::log);
    }

    private void interruptDownload(SyncState state, String relPath, byte[] changed) throws Exception {
        AtomicInteger checks = new AtomicInteger();
        try {
            download(state, relPath, changed, () -> checks.incrementAndGet() > 2);
            Assert.fail("The download was not interrupted");
        } catch (RuntimeException expected) {}
        Assert.assertFalse(state.getInProgressCopies().isEmpty());
    }

    @Test
    public void folderMovedAfterAnInterruptedDownload() throws Exception {
        for (boolean moveSupported : List.of(true, false)) {
            reset(moveSupported);
            SyncState state = new JdbcTreeState(":memory:");
            device.mkdirs(Paths.get("d"));
            byte[] old = "old".getBytes();
            device.setBytes(Paths.get("d/old.txt"), 0, AsyncReader.build(old), old.length, Optional.empty(), Optional.empty(),
                    Optional.empty(), ResumeUploadProps.random(crypto), () -> false, x -> {});
            sync(state);

            // more than one write, which is a MiB here
            byte[] data = randomText(3 * 1024 * 1024, 2);
            interruptDownload(state, "d/f.bin", data);
            Assert.assertFalse(device.exists(Paths.get("d/f.bin")));

            device.moveTo(Paths.get("d"), Paths.get("e"));
            sync(state);
            Map<String, String> expected = Map.of("d", "dir", "d/f.bin", new String(data), "e", "dir", "e/old.txt", "old");
            Assert.assertEquals("move " + moveSupported, expected, tree(device));
            Assert.assertEquals("move " + moveSupported, expected, tree(other));
            Assert.assertFalse(device.exists(Paths.get(LocalFileSystem.PARTIAL_COPIES_DIR)));
        }
    }

    @Test
    public void downloadReplacesAnExistingFile() throws Exception {
        for (boolean moveSupported : List.of(true, false)) {
            reset(moveSupported);
            SyncState state = new JdbcTreeState(":memory:");
            byte[] original = randomText(3 * 1024 * 1024, 1);
            device.setBytes(Paths.get("f.bin"), 0, AsyncReader.build(original), original.length, Optional.empty(),
                    Optional.empty(), Optional.empty(), ResumeUploadProps.random(crypto), () -> false, x -> {});
            sync(state);

            byte[] shorter = randomText(1024 * 1024 + 7, 2);
            interruptDownload(state, "f.bin", shorter);
            Assert.assertEquals(new String(original), tree(device).get("f.bin"));

            sync(state);
            Map<String, String> expected = Map.of("f.bin", new String(shorter));
            Assert.assertEquals("move " + moveSupported, expected, tree(device));
            Assert.assertEquals("move " + moveSupported, expected, tree(other));
            Assert.assertFalse(device.exists(Paths.get(LocalFileSystem.PARTIAL_COPIES_DIR)));
        }
    }

    @Test
    public void aFileTheProviderWouldNameAfterItsContent() throws Exception {
        for (boolean moveSupported : List.of(true, false)) {
            reset(moveSupported);
            SyncState state = new JdbcTreeState(":memory:");
            // a zip inside, under an extension in capitals: read by its first bytes it is a zip, and a provider adds
            // .zip to a name asked for as one that does not end in it
            byte[] data = ("PK\u0003\u0004" + new String(randomText(4096, 3))).getBytes();
            Files.write(otherRoot.resolve("Report.DOCX"), data);
            sync(state);
            Map<String, String> expected = Map.of("Report.DOCX", new String(data));
            Assert.assertEquals("move " + moveSupported, expected, tree(device));
            sync(state);
            Assert.assertEquals("move " + moveSupported, expected, tree(device));
            Assert.assertEquals("move " + moveSupported, expected, tree(other));
            Assert.assertFalse(device.exists(Paths.get(LocalFileSystem.PARTIAL_COPIES_DIR)));
        }
    }

    /** The platform's providers lower-case an extension in their own locale to find its type, and in Turkish the I of
     *  a capital one becomes a dotless i: .HEIC and .GIF are then of no type, and would get .heic and .gif added */
    @Test
    public void aCapitalExtensionInTurkish() throws Exception {
        reset(true, "tr");
        try {
            SyncState state = new JdbcTreeState(":memory:");
            Files.write(otherRoot.resolve("IMG_0001.HEIC"), "photo".getBytes());
            Files.write(otherRoot.resolve("clip.GIF"), "animation".getBytes());
            Map<String, String> expected = Map.of("IMG_0001.HEIC", "photo", "clip.GIF", "animation");
            sync(state);
            Assert.assertEquals(expected, tree(device));
            sync(state);
            Assert.assertEquals(expected, tree(device));
            Assert.assertEquals(expected, tree(other));
        } finally {
            reset(true);
        }
    }

    /** A name the storage cannot hold is skipped and reported, and the rest of the folder still syncs. The copy
     *  given up on is not resumed at the start of the next pass, which would fail it: the file is skipped again. */
    @Test
    public void aNameTheStorageCannotHoldIsSkipped() throws Exception {
        reset(true);
        SyncState state = new JdbcTreeState(":memory:");
        Files.write(otherRoot.resolve("a:b.txt"), "colon".getBytes());
        Files.write(otherRoot.resolve("c.txt"), "after it".getBytes());
        for (int pass = 0; pass < 2; pass++) {
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            DirectorySync.syncDir(device, other, true, true, null, null, state, 32, 5,
                    Files.createTempDirectory(ctx.getCacheDir().toPath(), "sync-state"), crypto, () -> false, log::add);
            Map<String, String> onDevice = tree(device);
            Assert.assertEquals("pass " + pass + ": " + log, "after it", onDevice.get("c.txt"));
            Assert.assertFalse(onDevice.containsKey("a_b.txt"));
            Assert.assertFalse(onDevice.containsKey("a:b.txt"));
            Assert.assertTrue("pass " + pass + ": " + log, log.stream().anyMatch(line -> line.contains("skipped")
                    && line.contains("its storage named it a_b.txt")));
        }
    }

    /** The sync has no skip for a folder, so the pass fails, but the files in it are skipped, the rest still syncs,
     *  and no folder is left under the name the storage chose. */
    @Test
    public void aFolderTheStorageCannotHoldLeavesNothingBehind() throws Exception {
        reset(true);
        SyncState state = new JdbcTreeState(":memory:");
        Files.createDirectories(otherRoot.resolve("n:1"));
        Files.write(otherRoot.resolve("n:1/x.txt"), "inside".getBytes());
        Files.write(otherRoot.resolve("c.txt"), "after it".getBytes());
        for (int pass = 0; pass < 2; pass++) {
            try {
                sync(state);
                Assert.fail("A folder the storage cannot hold was synced");
            } catch (Exception expected) {}
        }
        Map<String, String> onDevice = tree(device);
        Assert.assertEquals(onDevice.toString(), Map.of("c.txt", "after it"), onDevice);
    }

    /** The sync looks for a moved file under the name it was moved to, so one the storage can't hold is left as it was */
    @Test
    public void aRenameTheStorageCannotHoldLeavesTheFileAsItWas() throws Exception {
        reset(true);
        SyncState state = new JdbcTreeState(":memory:");
        Files.write(otherRoot.resolve("a.txt"), "renamed".getBytes());
        sync(state);
        Assert.assertEquals(Map.of("a.txt", "renamed"), tree(device));

        Files.move(otherRoot.resolve("a.txt"), otherRoot.resolve("a:x.txt"));
        try {
            sync(state);
            Assert.fail("A rename the storage cannot hold was synced");
        } catch (Exception expected) {}
        Assert.assertEquals(Map.of("a.txt", "renamed"), tree(device));
    }

    /** A new file is made before anything is read for it, so one whose read fails is removed again: left under its
     *  name, the next pass would take it for a file of its own */
    @Test
    public void aNewFileWhoseReadFailsIsNotLeft() throws Exception {
        reset(true);
        AsyncReader failing = new AsyncReader() {
            @Override
            public CompletableFuture<Integer> readIntoArray(byte[] res, int offset, int length) {
                CompletableFuture<Integer> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IOException("Gone"));
                return failed;
            }

            @Override
            public CompletableFuture<AsyncReader> reset() {
                return CompletableFuture.completedFuture(this);
            }

            @Override
            public void close() {}
        };
        try {
            device.setBytes(Paths.get("f.txt"), 0, failing, 10, Optional.empty(), Optional.empty(), Optional.empty(),
                    ResumeUploadProps.random(crypto), () -> false, x -> {});
            Assert.fail("Nothing could be read");
        } catch (Exception expected) {}
        Assert.assertEquals(Map.of(), tree(device));
    }

    @Test
    public void cannotCreateUnderAFile() throws Exception {
        reset(true);
        byte[] data = "file".getBytes();
        device.setBytes(Paths.get("b"), 0, AsyncReader.build(data), data.length, Optional.empty(), Optional.empty(),
                Optional.empty(), ResumeUploadProps.random(crypto), () -> false, x -> {});
        Assert.assertFalse(device.canCreate(Paths.get("b/x/doc.pdf")));
        Assert.assertTrue(device.canCreate(Paths.get("c/x/doc.pdf")));
    }
}
