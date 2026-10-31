package peergos.android;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Holds [::1] on the local server's port and passes its connections through to 127.0.0.1, where the server
 *  listens. The WebView resolves localhost to ::1 before 127.0.0.1, so whatever holds that port is sent the
 *  launch code and serves the sign in page: left free, any app on the phone could take it.
 */
final class LoopbackForwarder {

    private static ServerSocket held;

    private LoopbackForwarder() {}

    /** @return false if something else already holds [::1] on this port */
    static synchronized boolean claim(int port) {
        if (held != null)
            return true;
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getByName("::1"), port), 50);
        } catch (BindException e) {
            close(socket);
            // with no IPv6 loopback on this phone, the WebView has nothing to try there either
            String message = String.valueOf(e.getMessage());
            return message.contains("EADDRNOTAVAIL") || message.contains("Cannot assign requested address");
        } catch (IOException e) {
            close(socket);
            return false;
        }
        held = socket;
        ServerSocket listening = socket;
        Thread accepter = new Thread(() -> accept(listening, port), "loopback-forwarder");
        accepter.setDaemon(true);
        accepter.start();
        return true;
    }

    private static void accept(ServerSocket listening, int port) {
        while (true) {
            Socket client;
            try {
                client = listening.accept();
            } catch (IOException e) {
                if (listening.isClosed())
                    return;
                continue;
            }
            Thread connection = new Thread(() -> forward(client, port), "loopback-forwarder-connection");
            connection.setDaemon(true);
            connection.start();
        }
    }

    private static void forward(Socket client, int port) {
        try (Socket upstream = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            Thread back = new Thread(() -> pump(upstream, client), "loopback-forwarder-reply");
            back.setDaemon(true);
            back.start();
            pump(client, upstream);
            back.join();
        } catch (IOException | InterruptedException e) {
            // the server isn't up yet or went away: dropping the connection is the WebView's error page
        } finally {
            close(client);
        }
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[64 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
            to.shutdownOutput();
        } catch (IOException e) {
            close(to);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null)
            return;
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
