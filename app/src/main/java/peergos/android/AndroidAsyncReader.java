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
            int read = fin.read(bytes, offset, len);
            globalOffset.addAndGet(read);
            return Futures.of(read);
        } catch (IOException e) {
            return Futures.errored(e);
        }
    }

    @Override
    public CompletableFuture<AsyncReader> seek(long offset) {
        if (offset >= globalOffset.get()) {
            try {
                fin.skip(offset - globalOffset.get());
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
