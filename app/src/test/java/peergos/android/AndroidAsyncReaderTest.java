package peergos.android;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Random;

/**
 * Regression test for AndroidAsyncReader.seek (the mechanism AndroidSyncFileSystem.getBytes
 * relies on to position a reader at a diff-upload's start offset).
 *
 * InputStream.skip(n) is not guaranteed to skip n bytes — for content-provider / pipe backed
 * streams it routinely skips fewer and returns the actual count. seek() must honour that and
 * keep skipping until the full delta is consumed, otherwise the reader is left short of the
 * requested offset and every subsequent read returns bytes from the wrong position — which,
 * during a diff upload of an edited file, writes the wrong bytes into the remote chunk.
 */
public class AndroidAsyncReaderTest {

    /** Mimics a stream whose skip() under-skips: at most maxSkipPerCall bytes per call. */
    private static class ShortSkipStream extends ByteArrayInputStream {
        private final int maxSkipPerCall;
        ShortSkipStream(byte[] buf, int maxSkipPerCall) {
            super(buf);
            this.maxSkipPerCall = maxSkipPerCall;
        }
        @Override
        public synchronized long skip(long n) {
            return super.skip(Math.min(n, maxSkipPerCall));
        }
    }

    @Test
    public void seekLandsAtRequestedOffsetDespiteShortSkip() {
        byte[] data = new byte[100_000];
        new Random(42).nextBytes(data);
        int offset = 40_000;
        int len = 1000;

        AndroidAsyncReader reader = new AndroidAsyncReader(
                new ShortSkipStream(data, 7),           // skips at most 7 bytes per skip() call
                () -> new ShortSkipStream(data, 7));

        reader.seek(offset).join();

        byte[] got = new byte[len];
        int read = reader.readIntoArray(got, 0, len).join();
        Assert.assertEquals(len, read);

        byte[] expected = new byte[len];
        System.arraycopy(data, offset, expected, 0, len);
        Assert.assertArrayEquals(
                "seek must land at the requested offset even when the stream's skip() under-skips",
                expected, got);
    }
}
