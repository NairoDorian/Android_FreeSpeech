package dev.notune.transcribe;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BridgeAuthorizationTest {
    @Test public void acceptsOnlyThePairedPackageAndCapability() {
        assertTrue(BridgeAuthorization.isAuthorized("org.futo.inputmethod.latin.unstable", "secret",
                "org.futo.inputmethod.latin.unstable", "secret"));
        assertFalse(BridgeAuthorization.isAuthorized("org.futo.inputmethod.latin.unstable", "secret",
                "other.package", "secret"));
        assertFalse(BridgeAuthorization.isAuthorized("org.futo.inputmethod.latin.unstable", "secret",
                "org.futo.inputmethod.latin.unstable", "wrong"));
    }

    @Test public void rejectsMissingPairingMaterial() {
        assertFalse(BridgeAuthorization.isAuthorized(null, "secret", "pkg", "secret"));
        assertFalse(BridgeAuthorization.isAuthorized("pkg", null, "pkg", "secret"));
    }
}
