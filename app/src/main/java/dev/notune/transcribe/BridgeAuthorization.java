package dev.notune.transcribe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure checks used before any bridge command can access microphone work. */
final class BridgeAuthorization {
    private BridgeAuthorization() {}

    static boolean isAuthorized(String pairedPackage, String storedCapability,
            String callingPackage, String suppliedCapability) {
        boolean packageMatches = pairedPackage != null && callingPackage != null
                && (pairedPackage.equals(callingPackage)
                    || (pairedPackage.startsWith("org.futo.inputmethod.latin")
                        && callingPackage.startsWith("org.futo.inputmethod.latin")));
        return packageMatches && storedCapability != null
                && suppliedCapability != null
                && MessageDigest.isEqual(storedCapability.getBytes(StandardCharsets.UTF_8),
                        suppliedCapability.getBytes(StandardCharsets.UTF_8));
    }
}
