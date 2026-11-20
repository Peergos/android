package peergos.android;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The platform permits cleartext anywhere, so the poster is what keeps plain http to a server on this device,
 *  the local network or a VPN. */
public class AndroidPosterPlainHttpTest {

    private HttpServer server;
    private final AtomicInteger redirectTargetHits = new AtomicInteger();

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        // a 307 keeps the method and the body, so following it would send the request on as it is
        server.createContext("/redirect", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        // Android's client turns a POST into a GET on a 302, so that one would go on without the body
        server.createContext("/found", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> {
            redirectTargetHits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static Throwable cause(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    @Test(timeout = 30_000)
    public void plainHttpToThisDeviceWorks() {
        AndroidPoster poster = new AndroidPoster(url("http://127.0.0.1:" + server.getAddress().getPort() + "/"), false);
        byte[] res = poster.post("ok", new byte[]{1, 2, 3}, false, 5_000).join();
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), res);
    }

    @Test(timeout = 30_000)
    public void plainHttpToAPublicAddressIsRefusedBeforeConnecting() {
        for (String base : new String[]{"http://8.8.8.8:8000/", "http://example.com/", "http://010.0.0.1:8000/"}) {
            try {
                new AndroidPoster(url(base), false).post("api/v0/ids", new byte[0], false, 5_000).join();
                fail("plain http to " + base + " should be refused");
            } catch (CompletionException e) {
                Throwable cause = cause(e);
                assertTrue(base + ": " + cause, cause instanceof IOException && cause.getMessage().startsWith("Plain http is only for"));
            }
        }
    }

    @Test(timeout = 30_000)
    public void anAbsoluteUrlCannotTakePlainHttpElsewhere() {
        AndroidPoster poster = new AndroidPoster(url("http://127.0.0.1:" + server.getAddress().getPort() + "/"), false);
        try {
            poster.get("http://8.8.8.8:8000/api/v0/ids").join();
            fail("plain http to a public address should be refused");
        } catch (CompletionException e) {
            assertTrue(String.valueOf(cause(e)), cause(e).getMessage().startsWith("Plain http is only for"));
        }
    }

    @Test(timeout = 30_000)
    public void aRedirectIsNotFollowedOverPlainHttp() {
        AndroidPoster poster = new AndroidPoster(url("http://127.0.0.1:" + server.getAddress().getPort() + "/"), false);
        for (String[] redirect : new String[][]{{"redirect", "HTTP 307"}, {"found", "HTTP 302"}}) {
            try {
                poster.post(redirect[0], "secret".getBytes(StandardCharsets.UTF_8), false, 5_000).join();
                fail("a redirect should not be followed, nor taken as an empty answer");
            } catch (CompletionException e) {
                assertTrue(String.valueOf(cause(e)), cause(e).getMessage().startsWith(redirect[1]));
            }
        }
        assertEquals("the request went on to where it was redirected", 0, redirectTargetHits.get());
    }

    /** By the rule a server url is saved by, which an older core doesn't have: it refused a VPN, and looked names up. */
    @Test
    public void plainHttpToTheLocalNetworkOrAVpnIsLetThrough() throws IOException {
        for (String base : new String[]{"http://192.168.1.10:8000/", "http://100.64.0.10:8000/",
                "http://[fd7a:115c:a1e0::1]:8000/", "http://machine.tailnet.ts.net:8000/", "http://peer.netbird.cloud:8000/"})
            assertEquals(base + "api/v0/ids", new AndroidPoster(url(base), false).buildURL("api/v0/ids").toString());
    }

    /** A server on this device, the local network or a VPN is usually a private one, which answers a GET with a 405,
     *  over https as much as over http. */
    @Test
    public void aServerOnTheLocalNetworkOrAVpnIsNotTakenForAPublicOne() {
        for (String base : new String[]{"http://localhost:8000/", "http://192.168.1.10:8000/", "https://192.168.1.10:8443/",
                "https://100.64.0.10/", "https://[fd7a:115c:a1e0::1]/", "https://machine.tailnet.ts.net/"})
            assertFalse(base, AndroidPoster.isPublicServer(url(base)));
        for (String base : new String[]{"https://peergos.net/", "https://8.8.8.8/", "https://example.com/"})
            assertTrue(base, AndroidPoster.isPublicServer(url(base)));
    }

    private static URL url(String s) {
        try {
            return new URL(s);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
