package dev.notune.transcribe;

import android.app.Activity;

/** Transparent, one-shot activity that supplies while-in-use state for bridge promotion. */
public final class ForegroundActivationActivity extends Activity {
    private boolean dispatched;

    @Override public void onPostResume() {
        super.onPostResume();
        if (dispatched) return;
        dispatched = true;
        ForegroundActivationDispatcher.dispatchFromResumedActivity(getIntent(),
                this::startForegroundService);
        finish();
    }
}
