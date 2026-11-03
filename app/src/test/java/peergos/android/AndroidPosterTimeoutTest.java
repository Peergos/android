package peergos.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A request to a peer that stops taking part ends at its timeout, whether the peer never
 *  answers or never takes the body, so that whatever waits on it, an fsync through the
 *  Files-app mount for one, is told within a known time instead of for as long as TCP
 *  keeps trying. */
public class AndroidPosterTimeoutTest {

    /** Accepts connections and then neither reads nor writes. */
    private ServerSocket blackHole;
    private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());
    private AndroidPoster poster;

    @Before
    public void open() throws IOException {
        blackHole = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accept = new Thread(() -> {
            try {
                while (true) held.add(blackHole.accept());
            } catch (IOException closed) {
            }
        }, "black-hole");
        accept.setDaemon(true);
        accept.start();
        poster = new AndroidPoster(new URL("http://127.0.0.1:" + blackHole.getLocalPort() + "/"), true);
    }

    @After
    public void close() throws IOException {
        blackHole.close();
        for (Socket s : held) s.close();
    }

    /** More than the socket buffers on either side hold, so the send itself stalls. */
    private static final byte[] BIG = new byte[64 << 20];

    @Test(timeout = 30_000)
    public void aPutWhoseBodyIsNeverTakenEndsAtItsTimeout() {
        assertTimesOut(poster.put("block", BIG, Collections.emptyMap(), 1_000));
    }

    @Test(timeout = 30_000)
    public void aPutThatIsNeverAnsweredEndsAtItsTimeout() {
        assertTimesOut(poster.put("block", new byte[16], Collections.emptyMap(), 1_000));
    }

    @Test(timeout = 30_000)
    public void aPostWhoseBodyIsNeverTakenEndsAtItsTimeout() {
        assertTimesOut(poster.post("api", BIG, false, 1_000));
    }

    @Test(timeout = 30_000)
    public void aPostThatIsNeverAnsweredEndsAtItsTimeout() {
        assertTimesOut(poster.post("api", new byte[16], false, 1_000));
    }

    private static void assertTimesOut(CompletableFuture<byte[]> request) {
        long started = System.currentTimeMillis();
        try {
            request.join();
            fail("the request completed against a peer that never answers");
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            assertTrue("not reported as a timeout: " + cause, cause instanceof SocketTimeoutException);
        }
        long took = System.currentTimeMillis() - started;
        assertTrue("took " + took + " ms against a 1 s timeout", took < 10_000);
    }
}
