package peergos.android;

import org.peergos.util.Futures;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

import peergos.shared.user.HttpPoster;
import peergos.shared.util.Serialize;

public class AndroidPoster implements HttpPoster {
    /** Callers cap how many requests they have in flight, and that cap only means anything if a
     *  request returns before it is done. */
    private static final ExecutorService REQUESTS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AndroidPoster");
        t.setDaemon(true);
        return t;
    });

    /** Cuts a request off at its timeout. HttpURLConnection bounds a connect and each read,
     *  but not a body it is sending: a PUT of a block to a peer that stops taking bytes sits
     *  in write for as long as TCP keeps retrying. JavaPoster's timeout covers the whole
     *  request, and so does this one. */
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AndroidPoster-deadline");
        t.setDaemon(true);
        return t;
    });

    /** A timeout of 0 or less is none, as it is for HttpURLConnection's own. */
    private static final class Deadline {
        private final ScheduledFuture<?> cut;
        private volatile boolean passed;

        Deadline(HttpURLConnection conn, int timeoutMillis) {
            cut = timeoutMillis <= 0 ? null : DEADLINES.schedule(() -> {
                passed = true;
                conn.disconnect();
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        }

        boolean passed() {
            return passed;
        }

        void done() {
            if (cut != null)
                cut.cancel(false);
        }
    }

    private final URL dht;
    private final boolean useGet;
    private final Optional<String> basicAuth;

    public AndroidPoster(URL dht, boolean isPublicServer, Optional<String> basicAuth, Optional<String> userAgent) {
        this.dht = dht;
        this.useGet = isPublicServer;
        this.basicAuth = basicAuth;
    }

    public AndroidPoster(URL dht, boolean isPublicServer) {
        this(dht, isPublicServer, Optional.empty(), Optional.empty());
    }

    public URL buildURL(String method) throws IOException {
        try {
            return new URL(dht, method);
        } catch (MalformedURLException mexican) {
            throw new IOException(mexican);
        }
    }

    @Override
    public CompletableFuture<byte[]> postUnzip(String url, byte[] payload, int timeoutMillis) {
        return post(url, payload, true, timeoutMillis);
    }

    @Override
    public CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, int timeoutMillis) {
        return post(url, payload, unzip, Collections.emptyMap(), timeoutMillis);
    }

    /** The failure with its status code in the message, which a 404 otherwise loses:
     *  HttpURLConnection reports one as a FileNotFoundException naming only the url, and
     *  Exceptions.isUnimplemented reads that message to decide whether to fall back to the
     *  older call. The status goes in the message rather than into a wrapper around the
     *  original, because what reads one of these reports the cause whenever there is one.
     */
    private static IOException withStatus(HttpURLConnection conn, String url, IOException failure) {
        try {
            int status = conn.getResponseCode();
            if (status <= 0)
                return failure;
            String reason = conn.getResponseMessage();
            return new IOException("HTTP " + status
                    + (reason == null || reason.isEmpty() ? "" : ": " + reason)
                    + " retrieving " + url);
        } catch (IOException noStatus) {
            return failure;
        }
    }

    private CompletableFuture<byte[]> post(String url, byte[] payload, boolean unzip, Map<String, String> headers, int timeoutMillis) {
        return async(() -> postSync(url, payload, unzip, headers, timeoutMillis));
    }

    private CompletableFuture<byte[]> postSync(String url, byte[] payload, boolean unzip, Map<String, String> headers, int timeoutMillis) {
        HttpURLConnection conn = null;
        Deadline deadline = null;
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        try
        {
            conn = (HttpURLConnection) buildURL(url).openConnection();
            if (timeoutMillis >= 0)
                conn.setReadTimeout(timeoutMillis);
            conn.setConnectTimeout(1_000);
            deadline = new Deadline(conn, timeoutMillis);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (basicAuth.isPresent())
                conn.setRequestProperty("Authorization", basicAuth.get());
            try (DataOutputStream dout = new DataOutputStream(conn.getOutputStream())) {
                dout.write(payload);
                dout.flush();
            }

            String contentEncoding = conn.getContentEncoding();
            boolean isGzipped = "gzip".equals(contentEncoding);
            byte[] resp;
            try (DataInputStream din = new DataInputStream(isGzipped && unzip ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream())) {
                resp = Serialize.readFully(din);
            }
            res.complete(resp);
        } catch (SocketTimeoutException e) {
            res.completeExceptionally(new SocketTimeoutException("Socket timeout on: " + url));
        } catch (IOException | RuntimeException e) {
            // cut off at its deadline, the connection fails whatever it was doing in whatever way it does
            if (deadline != null && deadline.passed())
                res.completeExceptionally(new SocketTimeoutException("Socket timeout on: " + url));
            else if (! (e instanceof IOException))
                res.completeExceptionally(e);
            else if (conn != null){
                String trailer = conn.getHeaderField("Trailer");
                if (trailer != null)
                    System.err.println("Trailer:" + trailer);
                else
                    System.err.println(e.getMessage() + " retrieving " + url);
                res.completeExceptionally(trailer == null ?
                        withStatus(conn, url, (IOException) e) :
                        new RuntimeException(trailer));
            } else
                res.completeExceptionally(e);
        } finally {
            if (deadline != null)
                deadline.done();
            if (conn != null)
                try {
                    conn.disconnect();
                } catch (RuntimeException disconnectedAtItsDeadline) {
                }
        }
        return res;
    }

    @Override
    public CompletableFuture<byte[]> put(String url, byte[] body, Map<String, String> headers) {
        return put(url, body, headers, 60_000);
    }

    @Override
    public CompletableFuture<byte[]> put(String url, byte[] body, Map<String, String> headers, int timeoutMillis) {
        return async(() -> putSync(url, body, headers, timeoutMillis));
    }

    /** Runs a blocking request on the request pool. */
    private static CompletableFuture<byte[]> async(Supplier<CompletableFuture<byte[]>> request) {
        CompletableFuture<byte[]> res = new CompletableFuture<>();
        REQUESTS.execute(() -> {
            try {
                request.get().whenComplete((r, t) -> {
                    if (t != null)
                        res.completeExceptionally(t);
                    else
                        res.complete(r);
                });
            } catch (Throwable t) {
                res.completeExceptionally(t);
            }
        });
        return res;
    }

    private CompletableFuture<byte[]> putSync(String url, byte[] body, Map<String, String> headers, int timeoutMillis) {
        HttpURLConnection conn = null;
        Deadline deadline = null;
        try {
            conn = (HttpURLConnection) buildURL(url).openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(10_000);
            if (timeoutMillis >= 0)
                conn.setReadTimeout(timeoutMillis);
            deadline = new Deadline(conn, timeoutMillis);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
            if (basicAuth.isPresent())
                conn.setRequestProperty("Authorization", basicAuth.get());
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
                out.flush();
            }

            try (InputStream in = conn.getInputStream()) {
                return Futures.of(Serialize.readFully(in));
            }
        } catch (IOException | RuntimeException e) {
            CompletableFuture<byte[]> res = new CompletableFuture<>();
            if (e instanceof SocketTimeoutException || (deadline != null && deadline.passed()))
                res.completeExceptionally(new SocketTimeoutException("Socket timeout on: " + url));
            else if (! (e instanceof IOException))
                res.completeExceptionally(e);
            else if (conn != null) {
                try (InputStream err = conn.getErrorStream()) {
                    res.completeExceptionally(new IOException("HTTP " + conn.getResponseCode() + ": " + conn.getResponseMessage()));
                } catch (IOException f) {
                    res.completeExceptionally(f);
                }
            } else
                res.completeExceptionally(e);
            return res;
        } finally {
            if (deadline != null)
                deadline.done();
            if (conn != null)
                try {
                    conn.disconnect();
                } catch (RuntimeException disconnectedAtItsDeadline) {
                }
        }
    }

    @Override
    public CompletableFuture<byte[]> get(String url) {
        return get(url, Collections.emptyMap());
    }

    @Override
    public CompletableFuture<byte[]> get(String url, Map<String, String> headers) {
        if (useGet) {
            return publicGet(url, headers);
        } else {
            // This changes to a POST with an empty body
            // The reason for this is browsers allow any website to do a get request to localhost
            // but they block POST requests. So this prevents random websites from calling APIs on localhost
            return postUnzip(url, new byte[0]);
        }
    }

    private CompletableFuture<byte[]> publicGet(String url, Map<String, String> headers) {
        return CompletableFuture.supplyAsync(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) buildURL(url).openConnection();
                conn.setReadTimeout(15000);
                conn.setDoInput(true);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
                if (basicAuth.isPresent())
                    conn.setRequestProperty("Authorization", basicAuth.get());

                String contentEncoding = conn.getContentEncoding();
                boolean isGzipped = "gzip".equals(contentEncoding);
                try (DataInputStream din = new DataInputStream(isGzipped ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream())) {
                    return Serialize.readFully(din);
                }
            } catch (SocketTimeoutException e) {
                throw new RuntimeException("Timeout retrieving: " + url, e);
            } catch (IOException e) {
                String trailer = conn.getHeaderField("Trailer");
                if (trailer != null)
                    try {
                        throw new IllegalStateException(URLDecoder.decode(trailer, "UTF-8"));
                    } catch (UnsupportedEncodingException f) {}
                throw new RuntimeException(withStatus(conn, url, e));
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }, REQUESTS);
    }

    @Override
    public String toString() {
        return dht.toString();
    }
}
