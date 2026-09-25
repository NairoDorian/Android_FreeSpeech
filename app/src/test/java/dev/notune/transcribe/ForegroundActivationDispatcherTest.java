package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = ForegroundActivationDispatcherTest.ShadowOfflineVoiceBridgeService.class)
public class ForegroundActivationDispatcherTest {
    private static final String VALID_NONCE = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test public void resumedActivationRequestsBridgeForegroundService() {
        Intent activation = new Intent()
                .putExtra(OfflineVoiceBridgeService.EXTRA_FOREGROUND_NONCE, VALID_NONCE);
        RecordingStarter starter = new RecordingStarter();

        assertTrue(ForegroundActivationDispatcher.dispatchFromResumedActivity(activation, starter));
        assertEquals(1, starter.calls);
        assertEquals(OfflineVoiceBridgeService.class.getName(),
                starter.intent.getComponent().getClassName());
        assertEquals(OfflineVoiceBridgeService.ACTION_FOREGROUND_START, starter.intent.getAction());
        assertEquals(VALID_NONCE, starter.intent.getStringExtra(
                OfflineVoiceBridgeService.EXTRA_FOREGROUND_NONCE));
    }

    @Test public void malformedOrUnreadyActivationDoesNotRequestServiceStart() {
        RecordingStarter starter = new RecordingStarter();

        assertFalse(ForegroundActivationDispatcher.dispatchFromResumedActivity(new Intent(), starter));
        assertFalse(ForegroundActivationDispatcher.dispatchFromResumedActivity(
                new Intent().putExtra(OfflineVoiceBridgeService.EXTRA_FOREGROUND_NONCE, "not-a-nonce"),
                starter));
        assertEquals(0, starter.calls);
    }

    @Implements(OfflineVoiceBridgeService.class)
    public static final class ShadowOfflineVoiceBridgeService {
        @Implementation
        public static Intent foregroundStartIntent(String nonce) {
            return new Intent()
                    .setClassName(OfflineVoiceBridgeService.class.getPackage().getName(),
                            OfflineVoiceBridgeService.class.getName())
                    .setAction(OfflineVoiceBridgeService.ACTION_FOREGROUND_START)
                    .putExtra(OfflineVoiceBridgeService.EXTRA_FOREGROUND_NONCE, nonce);
        }
    }

    private static final class RecordingStarter implements ForegroundActivationDispatcher.ServiceStarter {
        int calls;
        Intent intent;

        @Override public void startForegroundService(Intent intent) {
            calls++;
            this.intent = intent;
        }
    }
}
