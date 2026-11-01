package peergos.android;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/** The forwarder holds [::1] on the local server's port so no other app can answer the WebView there. One test,
 *  because the forwarder is claimed once per process. */
public class LoopbackForwarderTest {

    private static final InetAddress V6 = address("::1");
    private static final InetAddress V4 = address("127.0.0.1");

    private static InetAddress address(String name) {
        try {
            return InetAddress.getByName(name);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Stands in for the local server: echoes each connection until the other side closes it, or sends a 'q' */
    private static ServerSocket echoServer() throws IOException {
        ServerSocket server = new ServerSocket(0, 200, V4);
        Thread accepter = new Thread(() -> {
            while (! server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread echo = new Thread(() -> {
                        try (Socket c = s) {
                            InputStream in = c.getInputStream();
                            OutputStream out = c.getOutputStream();
                            int b;
                            while ((b = in.read()) != -1 && b != 'q')
                                out.write(b);
                        } catch (IOException ignored) {}
                    });
                    echo.setDaemon(true);
                    echo.start();
                } catch (IOException ignored) {}
            }
        });
        accepter.setDaemon(true);
        accepter.start();
        return server;
    }

    private static Socket connect(int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(V6, port), 2_000);
        s.setSoTimeout(2_000);
        return s;
    }

    /** @return whether a byte sent on this connection comes back through the forwarder */
    private static boolean echoes(Socket s) {
        try {
            s.getOutputStream().write(42);
            return s.getInputStream().read() == 42;
        } catch (IOException e) {
            return false;
        }
    }

    /** @return whether a new connection is served within a few seconds */
    private static boolean servedAgain(int port) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (Socket again = connect(port)) {
                if (echoes(again))
                    return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    public void holdsTheV6LoopbackAndCapsItsConnections() throws Exception {
        try (ServerSocket probe = new ServerSocket(0, 50, V6)) {
        } catch (IOException e) {
            Assume.assumeNoException("no IPv6 loopback here", e);
        }

        // something else on [::1] is reported, so the app can refuse to load
        try (ServerSocket squatter = new ServerSocket(0, 50, V6)) {
            Assert.assertFalse(LoopbackForwarder.claim(squatter.getLocalPort()));
        }

        try (ServerSocket server = echoServer()) {
            int port = server.getLocalPort();
            Assert.assertTrue(LoopbackForwarder.claim(port));
            try (Socket first = connect(port)) {
                Assert.assertTrue(echoes(first));
            }

            List<Socket> open = new ArrayList<>();
            try {
                for (int i = 0; i < LoopbackForwarder.MAX_CONNECTIONS; i++) {
                    Socket s = connect(port);
                    open.add(s);
                    // a round trip, so the slot is surely taken before the next connection
                    Assert.assertTrue("connection " + i, echoes(s));
                }
                try (Socket extra = connect(port)) {
                    int read;
                    try {
                        extra.getOutputStream().write(42);
                        read = extra.getInputStream().read();
                    } catch (SocketTimeoutException e) {
                        throw new AssertionError("a connection past the cap was held open rather than dropped");
                    } catch (IOException e) {
                        read = -1;
                    }
                    Assert.assertEquals(-1, read);
                }

                // a closed connection gives its slot back
                open.remove(0).close();
                Assert.assertTrue(servedAgain(port));

                // and so does one the server has finished with, though the client keeps its side open
                for (int attempt = 0; attempt < 100 && open.size() < LoopbackForwarder.MAX_CONNECTIONS; attempt++) {
                    Socket s = connect(port);
                    if (echoes(s))
                        open.add(s);
                    else
                        s.close();
                }
                Assert.assertEquals(LoopbackForwarder.MAX_CONNECTIONS, open.size());
                try (Socket extra = connect(port)) {
                    Assert.assertFalse("the cap is full again", echoes(extra));
                }
                open.get(0).getOutputStream().write('q');
                Assert.assertTrue("a connection the server closed still holds its slot", servedAgain(port));
            } finally {
                for (Socket s : open)
                    s.close();
            }
        }
    }
}
