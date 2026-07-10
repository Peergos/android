package peergos.android;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import peergos.shared.user.fs.AsyncReader;
import peergos.shared.util.Futures;

public class AndroidAsyncReader implements AsyncReader {

    private final InputStream fin;
    private final AtomicLong globalOffset = new AtomicLong(0);
    private final Supplier<InputStream> resetter;

    public AndroidAsyncReader(InputStream fin, Supplier<InputStream> resetter) {
        this.fin = fin;
        this.resetter = resetter;
    }

    @Override
    public CompletableFuture<Integer> readIntoArray(byte[] bytes, int offset, int len) {
        try {
            int total = 0;
            while (total < len) {
                int read = fin.read(bytes, offset + total, len - total);
                if (read <= 0) // EOF
                    break;
                total += read;
            }
            globalOffset.addAndGet(total);
            return Futures.of(total);
        } catch (IOException e) {
            return Futures.errored(e);
        }
    }

    @Override
    public CompletableFuture<AsyncReader> seek(long offset) {
        if (offset >= globalOffset.get()) {
            try {
                long toSkip = offset - globalOffset.get();
                while (toSkip > 0) {
                    long skipped = fin.skip(toSkip);
                    if (skipped <= 0) {
                        // skip() can under-skip (or return 0) for pipe / content-provider backed
                        // streams; fall back to reading and discarding so we always advance fully.
                        int chunk = (int) Math.min(toSkip, 1 << 16);
                        int read = fin.read(new byte[chunk], 0, chunk);
                        if (read < 0)
                            throw new IOException("Reached EOF while seeking to " + offset);
                        skipped = read;
                    }
                    toSkip -= skipped;
                }
                globalOffset.set(offset);
                return Futures.of(this);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return reset().thenCompose(r -> r.seek(offset));
    }

    @Override
    public CompletableFuture<AsyncReader> reset() {
        return Futures.of(new AndroidAsyncReader(resetter.get(), resetter));
    }

    @Override
    public void close() {
        try {
            fin.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
