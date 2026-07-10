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

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;

import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.user.fs.ResumeUploadProps;

/**
 * Regression test for AndroidSyncFileSystem.truncate.
 *
 * truncate opens the file descriptor to shrink it; the mode string must be a real
 * ParcelFileDescriptor mode. A bare "t" is rejected by ParcelFileDescriptor.parseMode
 * (a mode must start with r/w), so truncate throws and shrink-syncs to an Android
 * device fail — the local file keeps its stale trailing bytes. This drives truncate
 * through the same SAF path the sync uses (DocumentFile over TestDocumentsProvider).
 */
@RunWith(AndroidJUnit4.class)
public class AndroidTruncateTest {

    private Context ctx;
    private Crypto crypto;
    private AndroidSyncFileSystem fs;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        crypto = Main.initCrypto(new ScryptAndroid());

        // Wipe the provider's root (count=0 seeds nothing).
        Uri authorityUri = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        Bundle seed = new Bundle();
        seed.putInt("count", 0);
        seed.putLong("seed", 1);
        seed.putLong("minSize", 0);
        seed.putLong("maxSize", 0);
        ctx.getContentResolver().call(authorityUri, TestDocumentsProvider.METHOD_RESET_AND_SEED, null, seed);

        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID);
        fs = new AndroidSyncFileSystem(treeUri, ctx, crypto);
    }

    @Test
    public void truncateShrinksFile() throws Exception {
        int size = 200_000;
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);

        Path p = fs.resolve("doc.bin");
        fs.setBytes(p, 0,
                new AndroidAsyncReader(new ByteArrayInputStream(data), () -> new ByteArrayInputStream(data)),
                size, Optional.empty(), Optional.empty(), Optional.empty(),
                ResumeUploadProps.random(crypto), () -> false, s -> {});
        Assert.assertEquals(size, fs.size(p));

        long newSize = size / 2;
        fs.truncate(p, newSize);   // throws on the current code (invalid mode "t")

        Assert.assertEquals(newSize, fs.size(p));
    }
}
