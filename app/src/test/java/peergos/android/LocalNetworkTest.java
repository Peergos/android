package peergos.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which servers Android 17 needs the local network permission for. */
public class LocalNetworkTest {

    @Test
    public void theLocalNetworkAndVpnsNeedThePermission() {
        for (String host : new String[]{"192.168.1.10", "10.0.0.5", "172.16.4.2", "169.254.1.1", "100.64.0.10",
                "[fd7a:115c:a1e0::1]", "[fe80::1]", "nas.local", "NAS.LOCAL"})
            assertTrue(host, LocalNetwork.isLocal(host));
    }

    @Test
    public void thisDeviceAndTheInternetDont() {
        for (String host : new String[]{"localhost", "127.0.0.1", "[::1]", "8.8.8.8", "100.128.0.1", "172.32.0.1",
                "[2001:db8::1]", "", null})
            assertFalse(String.valueOf(host), LocalNetwork.isLocal(host));
    }
}
