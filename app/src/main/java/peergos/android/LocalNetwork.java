package peergos.android;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** From Android 17 an app targeting it can't reach the local network without the runtime permission
 *  ACCESS_LOCAL_NETWORK (in the "Nearby devices" group): a connection there just times out. */
final class LocalNetwork {
    static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    /** Android 17 */
    static final int FIRST_SDK = 37;

    private LocalNetwork() {}

    /** Whether a server at this host is reached over the local network, which needs the permission: a private,
     *  shared (100.64/10, as mesh VPNs use) or link local address, a unique local IPv6 address, or a .local name.
     *  This device itself is not, as Android leaves loopback alone. A name is looked up, so not on the main thread.
     */
    static boolean isLocal(String host) {
        if (host == null || host.isEmpty())
            return false;
        String name = host.toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]"))
            name = name.substring(1, name.length() - 1);
        // resolved by multicast on the local network, which needs the permission itself
        if (name.endsWith(".local"))
            return true;
        try {
            for (InetAddress address : InetAddress.getAllByName(name))
                if (isLocal(address))
                    return true;
        } catch (UnknownHostException e) {
            // nothing to connect to, so nothing to ask for
        }
        return false;
    }

    static boolean isLocal(InetAddress address) {
        if (address.isLoopbackAddress())
            return false;
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress())
            return true;
        byte[] ip = address.getAddress();
        if (ip.length == 4)
            return (ip[0] & 0xff) == 100 && (ip[1] & 0xc0) == 64;
        return (ip[0] & 0xfe) == 0xfc;
    }
}
