package peergos.android;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Random;

import peergos.server.crypto.hash.ScryptJava;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.user.fs.Chunk;

public class ParallelHash {

    private static final int CHUNK = Chunk.LEGACY_SIZE;
    private static final Hasher HASHER = new ScryptJava();

    @Ignore("Slow")
    @Test
    public void parallel() {
        for (int size = 0; size < 1024*1024*1024; size += 3790000) {
            byte[] raw = new byte[size];
            new Random(42).nextBytes(raw);
            long t0 = System.nanoTime();
            List<byte[]> parallel = ScryptJava.parallelHashChunks(() -> new ByteArrayInputStream(raw), 8, size, CHUNK, HASHER);
            long t1 = System.nanoTime();

            List<byte[]> serial = ScryptJava.hashChunks(new ByteArrayInputStream(raw), size, 0, CHUNK, size, HASHER);
            long t2 = System.nanoTime();
            long sizeMiB = size / 1024 / 1024;
            if (sizeMiB > 0) {
                System.out.println("parallel took " + (t1 - t0) / 1_000_000_000 + ", serial took " + (t2 - t1) / 1_000_000000);
                System.out.println("Speed up " + (t2 - t1) / (t1 - t0) + " for file size " + sizeMiB + " MiB");
            }
            long expectedChunks = Math.max(1, (size + CHUNK - 1) / CHUNK);
            Assert.assertEquals(parallel.size(), expectedChunks);
            Assert.assertEquals(parallel.size(), serial.size());
            for (int i=0; i < parallel.size(); i++)
                Assert.assertArrayEquals(parallel.get(i), serial.get(i));
        }
    }
}
