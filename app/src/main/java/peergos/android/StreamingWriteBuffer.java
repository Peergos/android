package peergos.android;

import java.io.IOException;
import java.util.Arrays;

import peergos.shared.user.fs.Chunk;

/** Hands a writer's bytes to an uploader one chunk at a time, holding a single chunk in
 *  memory, so a file of any size can be uploaded without ever being stored locally.
 *
 *  {@link #write} blocks while the window is full — that backpressure is what paces the
 *  writing app to the upload and caps memory at one chunk. Writes must be sequential: the
 *  bytes behind the window have already been uploaded and can no longer be revised. */
class StreamingWriteBuffer {

    private final byte[] window = new byte[Chunk.MAX_SIZE];
    /** Absolute offset of window[0]. */
    private long windowStart;
    /** Bytes of the window the writer has filled. */
    private int filled;
    private boolean eof;
    private String aborted;

    synchronized void write(long offset, byte[] data, int len) throws IOException {
        checkLive();
        if (offset < windowStart)
            throw new IOException("Cannot rewrite offset " + offset + ", already uploaded through " + windowStart);
        if (offset > windowStart + filled)
            throw new IOException("Non-sequential write at " + offset + ", filled to " + (windowStart + filled));
        int done = 0;
        while (done < len) {
            while (offset + done >= windowStart + window.length) {
                await();
                checkLive();
            }
            int idx = (int) (offset + done - windowStart);
            int n = Math.min(len - done, window.length - idx);
            System.arraycopy(data, done, window, idx, n);
            done += n;
            if (idx + n > filled) filled = idx + n;
            notifyAll();
        }
    }

    synchronized void setEof() {
        eof = true;
        notifyAll();
    }

    /** Ends the transfer early — an upload that died must not leave the writer blocked
     *  forever waiting for a window that will never drain. */
    synchronized void abort(String reason) {
        aborted = reason;
        notifyAll();
    }

    /** Waits until a whole chunk is buffered or the writer is done, then reports how many
     *  bytes the next chunk holds — 0 once a file whose length is an exact multiple of the
     *  chunk size has been fully consumed. */
    synchronized int awaitChunk() throws IOException {
        while (filled < window.length && !eof) {
            await();
            checkLive();
        }
        checkLive();
        return filled;
    }

    /** Takes the buffered chunk and frees the window for the writer to refill. */
    synchronized byte[] takeChunk() {
        byte[] chunk = Arrays.copyOf(window, filled);
        windowStart += filled;
        filled = 0;
        notifyAll();
        return chunk;
    }

    private void await() throws IOException {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted streaming upload");
        }
    }

    private void checkLive() throws IOException {
        if (aborted != null) throw new IOException("Upload aborted: " + aborted);
    }
}
