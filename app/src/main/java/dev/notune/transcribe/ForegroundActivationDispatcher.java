package dev.notune.transcribe;

import android.content.Intent;
import android.util.Base64;

/** Dispatches a nonce-bearing bridge start only after the activation activity is resumed. */
final class ForegroundActivationDispatcher {
    interface ServiceStarter {
        void startForegroundService(Intent intent);
    }

    private ForegroundActivationDispatcher() { }

    static boolean dispatchFromResumedActivity(Intent activationIntent, ServiceStarter starter) {
        if (activationIntent == null || starter == null) return false;
        String nonce = activationIntent.getStringExtra(OfflineVoiceBridgeService.EXTRA_FOREGROUND_NONCE);
        if (!isNonce(nonce)) return false;
        starter.startForegroundService(OfflineVoiceBridgeService.foregroundStartIntent(nonce));
        return true;
    }

    private static boolean isNonce(String nonce) {
        if (nonce == null) return false;
        try {
            return Base64.decode(nonce, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP).length == 32;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
