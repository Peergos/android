package peergos.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** AndroidPosterTimeoutTest on the device: Android's HttpURLConnection is OkHttp's, not the
 *  JDK's, and cutting a connection from another thread while it sends has to end the send
 *  there too. The body is as much as the app's heap can spare, and the sink takes as little
 *  as a socket can, so the send blocks. */
@RunWith(AndroidJUnit4.class)
public class AndroidPosterDeviceTimeoutTest {

    /** Accepts connections and then neither reads nor writes. */
    private ServerSocket blackHole;
    private final List<Socket> held = Collections.synchronizedList(new ArrayList<>());
    private AndroidPoster poster;

    @Before
    public void open() throws IOException {
        blackHole = new ServerSocket();
        blackHole.setReceiveBufferSize(4096);
        blackHole.bind(new InetSocketAddress(0));
        Thread accept = new Thread(() -> {
            try {
                while (true) held.add(blackHole.accept());
            } catch (IOException closed) {
            }
        }, "black-hole");
        accept.setDaemon(true);
        accept.start();
        poster = new AndroidPoster(new URL("http://localhost:" + blackHole.getLocalPort() + "/"), true);
    }

    @After
    public void close() throws IOException {
        blackHole.close();
        for (Socket s : held) s.close();
    }

    /** More than the socket buffers on either side hold, so the send itself stalls. */
    private static final byte[] BIG = new byte[8 << 20];

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

    /** A request that ends, however it ends, takes its deadline out of the queue with it: left there, each would
     *  keep its connection, and on Android the body it buffered, until the time it would have fired. */
    @Test(timeout = 30_000)
    public void aFinishedRequestLeavesNoDeadlineBehind() throws IOException {
        int closedPort;
        try (ServerSocket taken = new ServerSocket(0)) {
            closedPort = taken.getLocalPort();
        }
        AndroidPoster refused = new AndroidPoster(new URL("http://localhost:" + closedPort + "/"), true);
        for (CompletableFuture<byte[]> request : List.of(
                refused.put("block", new byte[16], Collections.emptyMap(), 60_000),
                refused.post("api", new byte[16], false, 60_000))) {
            try {
                request.join();
                fail("a request to a closed port completed");
            } catch (CompletionException expected) {
            }
        }
        assertEquals("deadlines left scheduled by finished requests", 0, AndroidPoster.deadlinesPending());
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
