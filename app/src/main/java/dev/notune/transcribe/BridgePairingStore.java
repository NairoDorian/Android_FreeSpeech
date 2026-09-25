package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Base64;

import java.security.SecureRandom;

/** Private pairing record. Capability values must never be logged. */
final class BridgePairingStore {
    // The custom APK is assembled as FUTO's unstable flavor. Its applicationId
    // carries this suffix, while the namespace and pairing action do not.
    static final String FUTO_PACKAGE = "org.futo.inputmethod.latin.unstable";
    static final String FUTO_STABLE_PACKAGE = "org.futo.inputmethod.latin";
    static final String FUTO_PAIR_ACTION =
            "org.futo.inputmethod.latin.action.PAIR_OFFLINE_VOICE_BRIDGE";
    static final String EXTRA_CAPABILITY = "dev.notune.transcribe.extra.PAIRING_CAPABILITY";
    static final String EXTRA_ACCEPTED = "dev.notune.transcribe.extra.PAIRING_ACCEPTED";
    private static final String PREFS = "offline_voice_bridge";
    private static final String PACKAGE = "package";
    private static final String CAPABILITY = "capability";

    private BridgePairingStore() {}

    static String createCapability() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes,
                Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
    }

    static void save(Context context, String packageName, String capability) {
        preferences(context).edit().putString(PACKAGE, packageName)
                .putString(CAPABILITY, capability).apply();
    }

    static void revoke(Context context) {
        preferences(context).edit().clear().apply();
    }

    static String packageName(Context context) { return preferences(context).getString(PACKAGE, null); }
    static String capability(Context context) { return preferences(context).getString(CAPABILITY, null); }
    static boolean isPaired(Context context) {
        return packageName(context) != null && capability(context) != null;
    }

    static String resolveInstalledFutoPackage(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(FUTO_PACKAGE, 0);
            return FUTO_PACKAGE;
        } catch (PackageManager.NameNotFoundException ignored) {}
        try {
            pm.getPackageInfo(FUTO_STABLE_PACKAGE, 0);
            return FUTO_STABLE_PACKAGE;
        } catch (PackageManager.NameNotFoundException ignored) {}
        return FUTO_PACKAGE;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
