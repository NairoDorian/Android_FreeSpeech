package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Test;

/** Behavioral coverage for the Java/native Android-context handoff. */
public class NativeContextBootstrapTest {
    @Test public void initializesOnceWithTheApplicationContext() {
        Context application = new ContextWrapper(null);
        Context caller = new ContextWrapper(application) {
            @Override public Context getApplicationContext() {
                return application;
            }
        };
        RecordingBinding binding = new RecordingBinding();
        NativeContextBootstrap bootstrap = new NativeContextBootstrap(binding);

        bootstrap.initialize(caller);
        bootstrap.initialize(caller);

        assertEquals(1, binding.calls);
        assertEquals(application, binding.context);
    }

    private static final class RecordingBinding implements NativeContextBootstrap.Binding {
        int calls;
        Context context;

        @Override public void initialize(Context context) {
            calls++;
            this.context = context;
        }
    }
}
