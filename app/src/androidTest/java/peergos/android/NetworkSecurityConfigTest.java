package peergos.android;

import android.security.NetworkSecurityPolicy;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The platform permits cleartext but to peergos.net, as it can't name the ranges AndroidPoster allows it to. */
@RunWith(AndroidJUnit4.class)
public class NetworkSecurityConfigTest {

    @Test
    public void neverPlainHttpToPeergosNet() {
        NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
        assertFalse(policy.isCleartextTrafficPermitted("peergos.net"));
        assertFalse(policy.isCleartextTrafficPermitted("www.peergos.net"));
        assertTrue(policy.isCleartextTrafficPermitted("localhost"));
        assertTrue(policy.isCleartextTrafficPermitted("192.168.1.10"));
        assertTrue(policy.isCleartextTrafficPermitted("100.64.0.10"));
    }
}
