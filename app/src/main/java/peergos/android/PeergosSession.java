package peergos.android;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import peergos.server.net.MountConfigHandler;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;

public final class PeergosSession {

    private static volatile UserContext context;
    private static volatile NetworkAccess network;
    private static volatile Crypto crypto;
    private static volatile MountConfigHandler mountHandler;
    /** How long a request waits for the saved mount's sign-in at start, which takes a second or two. */
    public static final long SIGN_IN_WAIT_MILLIS = 20_000;
    /** Set at process start while a saved mount signs in, and let go by the session it publishes. */
    private static volatile CountDownLatch signingIn;

    private PeergosSession() {}

    public static void publish(UserContext c, NetworkAccess n, Crypto cr) {
        context = c;
        network = n;
        crypto = cr;
        CountDownLatch waiting = signingIn;
        if (waiting != null)
            waiting.countDown();
    }

    /** A saved mount is signing in, so a session is on its way. */
    public static void signingIn() {
        signingIn = new CountDownLatch(1);
    }

    /** The sign-in at start will not happen after all: nothing waits for it. */
    public static void notSigningIn() {
        CountDownLatch waiting = signingIn;
        signingIn = null;
        if (waiting != null)
            waiting.countDown();
    }

    /** The session, waiting up to millis for one a sign-in at start is on its way to publishing.
     *
     *  The process is often started for a request: an app reopening a document it holds a grant for, right after
     *  Peergos' process was stopped. Answered "not signed in" while the mount was a second or two from being,
     *  some apps took the document for gone and dropped it. Waited for once: a sign-in that has not published
     *  in that time has failed, and later requests are answered at once. */
    public static Optional<UserContext> awaitContext(long millis) throws InterruptedException {
        CountDownLatch waiting = signingIn;
        if (context == null && waiting != null) {
            waiting.await(millis, TimeUnit.MILLISECONDS);
            signingIn = null;
        }
        return context();
    }

    public static void clear() {
        context = null;
        network = null;
        crypto = null;
    }

    public static void setMountHandler(MountConfigHandler h) { mountHandler = h; }
    public static Optional<MountConfigHandler> mountHandler() { return Optional.ofNullable(mountHandler); }

    public static Optional<UserContext> context() { return Optional.ofNullable(context); }
    public static Optional<NetworkAccess> network() { return Optional.ofNullable(network); }
    public static Optional<Crypto> crypto() { return Optional.ofNullable(crypto); }
}
