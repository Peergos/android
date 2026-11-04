package peergos.android;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /** Six times what the socket buffers hold, so most of it goes as the peer takes it */
    private static final byte[] SLOW = new byte[24 << 20];

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
        AndroidPoster refused = new AndroidPoster(new URL("http://127.0.0.1:" + closedPort + "/"), true);
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

    /** Takes each request at a steady rate, as a slow uplink lets it through, and answers it once it has
     *  all of it. */
    private static ServerSocket slowUplink(int bytesPerSecond) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReceiveBufferSize(64 << 10);
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread serve = new Thread(() -> {
            while (! server.isClosed())
                try (Socket s = server.accept()) {
                    takeSlowly(s, bytesPerSecond);
                } catch (IOException | InterruptedException dropped) {
                }
        }, "slow-uplink");
        serve.setDaemon(true);
        serve.start();
        return server;
    }

    private static void takeSlowly(Socket s, int bytesPerSecond) throws IOException, InterruptedException {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (! head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0)
                return;
            head.append((char) b);
        }
        long length = 0;
        for (String line : head.toString().split("\r\n"))
            if (line.toLowerCase().startsWith("content-length:"))
                length = Long.parseLong(line.substring(15).trim());
        byte[] piece = new byte[16 << 10];
        long started = System.nanoTime();
        for (long taken = 0; taken < length; ) {
            int n = in.read(piece, 0, (int) Math.min(piece.length, length - taken));
            if (n < 0)
                return;
            taken += n;
            long ahead = taken * 1_000_000_000L / bytesPerSecond - (System.nanoTime() - started);
            if (ahead > 0)
                Thread.sleep(ahead / 1_000_000, (int) (ahead % 1_000_000));
        }
        s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII));
    }

    /** An upload that keeps going is not cut at its timeout, however long it takes: a block on a slow mobile
     *  uplink, or on one that several blocks share, takes longer than its timeout to send, and would be cut the
     *  same way on every retry. Each takes 4 s here, and what the socket buffers still hold after the last
     *  write, at most 4 MiB, goes in under a second. */
    @Test(timeout = 30_000)
    public void aBodyThatKeepsGoingIsNotCutAtItsTimeout() throws IOException {
        try (ServerSocket slow = slowUplink(6 << 20)) {
            AndroidPoster p = new AndroidPoster(new URL("http://127.0.0.1:" + slow.getLocalPort() + "/"), true);
            p.put("block", SLOW, Collections.emptyMap(), 2_000).join();
            p.post("api", SLOW, false, 2_000).join();
        }
    }

    /** Holds the deadline's check for the test to run when it chooses, as if it had started at that moment. */
    private static final class HeldTimer extends ScheduledThreadPoolExecutor {
        volatile Runnable held;

        HeldTimer() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            held = task;
            return super.schedule(task, 1, TimeUnit.DAYS);
        }
    }

    private static final class Cuttable extends HttpURLConnection {
        volatile boolean cut;

        Cuttable() throws MalformedURLException {
            super(new URL("http://127.0.0.1/"));
        }

        @Override
        public void disconnect() {
            cut = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }

    /** An answer that arrives as its deadline fires is the answer, not a timeout: cut then, a POST that succeeded
     *  would be reported as timed out, and retried. */
    @Test(timeout = 30_000)
    public void anAnswerThatArrivesAsItsDeadlineFiresIsNotCut() throws Exception {
        HeldTimer timer = new HeldTimer();
        try {
            Cuttable conn = new Cuttable();
            AndroidPoster.Deadline deadline = new AndroidPoster.Deadline(conn, 1, timer);
            Thread.sleep(20); // its whole timeout passes, so its check is due
            deadline.done(); // as the answer arrives
            timer.held.run(); // and the check, already started, goes on
            assertFalse("the connection was cut after its answer had arrived", conn.cut);
            assertFalse("an answer in time was reported as a timeout", deadline.passed());
        } finally {
            timer.shutdownNow();
        }
    }

    /** A get that fails before it has a connection says why, rather than with a NullPointerException. */
    @Test(timeout = 30_000)
    public void aGetThatFailsBeforeItConnectsSaysWhy() {
        try {
            poster.get("nosuch://block").join();
            fail("a get of a url with no protocol handler completed");
        } catch (CompletionException e) {
            Throwable why = e.getCause();
            while (why != null && ! (why instanceof MalformedURLException))
                why = why.getCause();
            assertTrue("the real error was lost: " + e.getCause(), why != null);
        }
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
