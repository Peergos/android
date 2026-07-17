package peergos.android;

import java.util.Optional;

import peergos.server.net.MountConfigHandler;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;

public final class PeergosSession {

    private static volatile UserContext context;
    private static volatile NetworkAccess network;
    private static volatile Crypto crypto;
    private static volatile MountConfigHandler mountHandler;

    private PeergosSession() {}

    public static void publish(UserContext c, NetworkAccess n, Crypto cr) {
        context = c;
        network = n;
        crypto = cr;
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
