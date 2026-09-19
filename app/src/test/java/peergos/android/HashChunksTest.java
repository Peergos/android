package peergos.android;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import peergos.server.crypto.hash.ScryptJava;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.Chunk;

/**
 * Regression test for the chunk hashing android's sync drives, now
 * {@link ScryptJava#hashChunks} rather than a copy of it in AndroidSyncFileSystem.
 *
 * The per-chunk hash must be SHA-256 over that chunk's exact byte range, independent of how
 * the underlying InputStream chunks its reads. It reads with a plain fin.read(buf), which is
 * NOT guaranteed to fill the buffer (content-provider / SAF backed file descriptors regularly
 * return short reads). If a read straddles a chunk boundary, the bytes past the boundary
 * ("leftover") must be carried into the next chunk's digest.
 *
 * The buggy version reset chunkOffset to 0 and dropped those leftover bytes, so every chunk
 * after the first straddling read diverged from the true content hash. The bug was in
 * android's copy, which is why this test lives here: the short reads it is about come from
 * android's file descriptors, even though the code under test is now shared.
 */
public class HashChunksTest {

    private static final int CHUNK = Chunk.LEGACY_SIZE;
    private static final Hasher HASHER = new ScryptJava();

    /** SHA-256 of each exact [i*MAX, (i+1)*MAX) slice — the correct answer by definition. */
    private static List<byte[]> groundTruth(byte[] data) throws Exception {
        List<byte[]> out = new ArrayList<>();
        if (data.length == 0) {
            out.add(MessageDigest.getInstance("SHA-256").digest());
            return out;
        }
        for (int start = 0; start < data.length; start += CHUNK) {
            int end = Math.min(data.length, start + CHUNK);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data, start, end - start);
            out.add(md.digest());
        }
        return out;
    }

    /** ByteArrayInputStream that returns one deliberately short read to shift buffer alignment
     *  so a later read straddles a chunk boundary — mimicking a content-provider FD. */
    private static class ShortFirstRead extends ByteArrayInputStream {
        private boolean shortened = false;
        ShortFirstRead(byte[] buf) { super(buf); }
        @Override
        public synchronized int read(byte[] b, int off, int len) {
            if (!shortened) {
                shortened = true;
                return super.read(b, off, Math.min(len, 40_000));
            }
            return super.read(b, off, len);
        }
        @Override
        public synchronized int read(byte[] b) {
            return read(b, 0, b.length);
        }
    }

    private static void assertChunksMatch(List<byte[]> expected, List<byte[]> actual) {
        Assert.assertEquals("chunk count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            Assert.assertArrayEquals("chunk " + i + " hash", expected.get(i), actual.get(i));
    }

    @Test
    public void hashChunksIsCorrectWithShortReads() throws Exception {
        // 3 chunks (two full + a partial), enough to expose a boundary-straddling read.
        int size = 2 * CHUNK + 7_777;
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);

        List<byte[]> expected = groundTruth(data);

        // Sanity: with full reads the alignment is preserved and the hash is already correct.
        assertChunksMatch(expected, ScryptJava.hashChunks(new ByteArrayInputStream(data), size, 0, CHUNK, size, HASHER));

        // The real test: a short read must not corrupt the chunk hashes.
        assertChunksMatch(expected, ScryptJava.hashChunks(new ShortFirstRead(data), size, 0, CHUNK, size, HASHER));
    }

    @Test
    public void parallelHashChunksIsCorrectWithShortReads() throws Exception {
        int size = 2 * CHUNK + 7_777;
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);

        List<byte[]> expected = groundTruth(data);
        assertChunksMatch(expected, ScryptJava.parallelHashChunks(() -> new ShortFirstRead(data), 8, size, CHUNK, HASHER));
    }
}
