package peergos.android;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/** From Android 17 an app targeting it can't reach the local network without the runtime permission
 *  ACCESS_LOCAL_NETWORK (in the "Nearby devices" group): a connection there just times out. */
final class LocalNetwork {
    static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    /** Android 17 */
    static final int FIRST_SDK = 37;

    private LocalNetwork() {}

    /** An address of this device, with the length of the prefix it shares with its subnet. */
    static final class Subnet {
        final InetAddress address;
        final int prefixLength;

        Subnet(InetAddress address, int prefixLength) {
            this.address = address;
            this.prefixLength = prefixLength;
        }

        boolean contains(InetAddress other) {
            return inPrefix(other.getAddress(), address.getAddress(), prefixLength);
        }

        /** As Android tells a local subnet: any IPv6 one, and an IPv4 one inside a private, shared or link local
         *  range (ConnectivityService.isPrefixLocal). */
        boolean isLocal() {
            byte[] ip = address.getAddress();
            if (prefixLength <= 0 || prefixLength > ip.length * 8)
                return false;
            if (ip.length == 16)
                return true;
            for (int[] range : IPV4_LOCAL)
                if (prefixLength >= range[4] && inPrefix(ip, new byte[]{(byte) range[0], (byte) range[1], (byte) range[2], (byte) range[3]}, range[4]))
                    return true;
            return false;
        }
    }

    private static final int[][] IPV4_LOCAL = {{10, 0, 0, 0, 8}, {172, 16, 0, 0, 12}, {192, 168, 0, 0, 16},
            {169, 254, 0, 0, 16}, {100, 64, 0, 0, 10}};

    private static boolean inPrefix(byte[] ip, byte[] prefix, int bits) {
        if (ip.length != prefix.length || bits < 0 || bits > ip.length * 8)
            return false;
        for (int i = 0; i < bits; i++) {
            int mask = 0x80 >> (i % 8);
            if ((ip[i / 8] & mask) != (prefix[i / 8] & mask))
                return false;
        }
        return true;
    }

    /** This device's addresses on all its interfaces, VPNs included. */
    static List<Subnet> ownSubnets() {
        List<Subnet> own = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces != null)
                for (NetworkInterface iface : Collections.list(ifaces))
                    for (InterfaceAddress address : iface.getInterfaceAddresses())
                        own.add(new Subnet(address.getAddress(), address.getNetworkPrefixLength()));
        } catch (SocketException e) {
            // no interfaces to read, so nothing is local
        }
        return own;
    }

    /** Whether a server at this host is on the local network, which needs the permission. Android 17 counts a
     *  destination on the subnet of one of the device's own addresses as local, a VPN's included, and this
     *  approximates it. So a VPN that gives the device a single address, as Tailscale does, leaves its peers alone,
     *  but one that gives it a /16 doesn't. A private or link local address, a VPN peer's included, and a .local
     *  name are local too, and this device by loopback isn't. A name is looked up, so not on the main thread.
     */
    static boolean isLocal(String host, List<Subnet> own) {
        if (host == null || host.isEmpty())
            return false;
        String name = host.toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]"))
            name = name.substring(1, name.length() - 1);
        // resolved by multicast on the local network, which needs the permission itself
        if (name.endsWith(".local"))
            return true;
        try {
            for (InetAddress address : InetAddress.getAllByName(name)) {
                if (address.isLoopbackAddress())
                    continue;
                // a server on the local network, which the device may not be on yet when the app starts: ask up front
                if (address.isSiteLocalAddress() || address.isLinkLocalAddress())
                    return true;
                for (Subnet subnet : own)
                    if (subnet.isLocal() && subnet.contains(address))
                        return true;
            }
        } catch (UnknownHostException e) {
            // nothing to connect to, so nothing to ask for
        }
        return false;
    }
}
