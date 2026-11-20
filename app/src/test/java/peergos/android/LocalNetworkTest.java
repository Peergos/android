package peergos.android;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which servers Android 17 needs the local network permission for. It counts a destination on the subnet of one of
 *  the device's own addresses as local (ConnectivityService.getEffectiveLocalPrefixes and isPrefixLocal). */
public class LocalNetworkTest {

    private static LocalNetwork.Subnet subnet(String address, int prefixLength) {
        return new LocalNetwork.Subnet(ip(address), prefixLength);
    }

    private static InetAddress ip(String address) {
        try {
            return InetAddress.getByName(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    // home Wi-Fi with a global IPv6 prefix, a VPN giving the device single addresses (as Tailscale does), and one
    // giving it a /16 (as NetBird's Android client did before 0.6.0)
    private static final List<LocalNetwork.Subnet> PHONE = Arrays.asList(
            subnet("192.168.1.23", 24), subnet("2001:db8:a:b::23", 64), subnet("fe80::23", 64),
            subnet("100.101.102.103", 32), subnet("fd7a:115c:a1e0::23", 128),
            subnet("100.80.1.2", 16),
            subnet("127.0.0.1", 8), subnet("::1", 128));

    @Test
    public void aServerOnTheSubnetOfOneOfTheDevicesAddressesNeedsThePermission() {
        for (String host : new String[]{"192.168.1.10", "[2001:db8:a:b::10]", "[fe80::10]", "100.80.5.6", "nas.local", "NAS.LOCAL"})
            assertTrue(host, LocalNetwork.isLocal(host, PHONE));
    }

    /** The device may not be on the network yet when the app starts, as on mobile data, and is asked once up front. */
    @Test
    public void aPrivateOrLinkLocalAddressNeedsItWhateverNetworkTheDeviceIsOn() {
        for (String host : new String[]{"192.168.1.10", "192.168.2.10", "10.0.0.5", "172.16.4.2", "169.254.1.1", "[fe80::10]"})
            assertTrue(host, LocalNetwork.isLocal(host, Collections.emptyList()));
    }

    @Test
    public void aVpnPeerOrAServerOnlyRoutedToDoesNot() {
        // VPN peers behind a single address, or outside the VPN's /16, and the internet
        for (String host : new String[]{"100.64.0.10", "[fd7a:115c:a1e0::10]", "100.81.0.1", "[2001:db8:a:c::10]", "8.8.8.8"})
            assertFalse(host, LocalNetwork.isLocal(host, PHONE));
    }

    @Test
    public void aSubnetIsOnlyLocalWithAPrefixLengthThatFitsTheAddress() {
        assertTrue(LocalNetwork.isLocal("100.64.0.4", Arrays.asList(subnet("100.64.0.3", 24))));
        assertTrue(LocalNetwork.isLocal("[fd00::4]", Arrays.asList(subnet("fd00::3", 64))));
        // wider than the shared range it starts in, as on a VPN or mobile data, where isPrefixLocal alone decides
        assertFalse(LocalNetwork.isLocal("100.1.2.3", Arrays.asList(subnet("100.64.0.3", 8))));
        for (int bad : new int[]{0, -1, 33})
            assertFalse("/" + bad, LocalNetwork.isLocal("100.64.0.4", Arrays.asList(subnet("100.64.0.3", bad))));
        for (int bad : new int[]{0, -1, 129})
            assertFalse("/" + bad, LocalNetwork.isLocal("[fd00::4]", Arrays.asList(subnet("fd00::3", bad))));
    }

    @Test
    public void aSubnetIsMatchedBitByBit() {
        List<LocalNetwork.Subnet> shared = Arrays.asList(subnet("100.64.0.3", 10));
        for (String host : new String[]{"100.65.0.1", "100.127.255.254"})
            assertTrue(host, LocalNetwork.isLocal(host, shared));
        assertFalse(LocalNetwork.isLocal("100.128.0.1", shared));
        // outside the shared range, though on the device's subnet
        assertFalse(LocalNetwork.isLocal("100.1.2.4", Arrays.asList(subnet("100.1.2.3", 24))));
        // differs only in the first bit
        assertFalse(LocalNetwork.isLocal("[7d00::4]", Arrays.asList(subnet("fd00::3", 64))));
        // an IPv6 address is never on an IPv4 subnet, whatever its first bytes
        assertFalse(LocalNetwork.isLocal("[6440::4]", Arrays.asList(subnet("100.64.0.3", 24))));
    }

    @Test
    public void thisDeviceAndNothingAtAllDont() {
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "", null})
            assertFalse(String.valueOf(host), LocalNetwork.isLocal(host, PHONE));
    }
}
