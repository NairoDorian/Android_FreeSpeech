package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure checks used before any bridge command can access microphone work. */
final class BridgeAuthorization {
    private BridgeAuthorization() {}

    static boolean isAuthorized(String pairedPackage, String storedCapability,
            String callingPackage, String suppliedCapability) {
        return pairedPackage != null && storedCapability != null
                && pairedPackage.equals(callingPackage)
                && suppliedCapability != null
                && MessageDigest.isEqual(storedCapability.getBytes(StandardCharsets.UTF_8),
                        suppliedCapability.getBytes(StandardCharsets.UTF_8));
    }
}
