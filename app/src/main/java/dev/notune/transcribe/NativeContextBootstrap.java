package dev.notune.transcribe;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

/** Initializes the Android context required by CPAL before any audio path can run. */
final class NativeContextBootstrap {
    interface Binding {
        void initialize(Context applicationContext);
    }

    private static final Binding NATIVE_BINDING = new Binding() {
        @Override public void initialize(Context applicationContext) {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
            if (!initializeNative(applicationContext)) {
                throw new IllegalStateException("Unable to initialize the native audio runtime.");
            }
        }
    };

    private final Binding binding;
    private final AtomicBoolean initialized = new AtomicBoolean();

    NativeContextBootstrap() {
        this(NATIVE_BINDING);
    }

    NativeContextBootstrap(Binding binding) {
        this.binding = binding;
    }

    void initialize(Context context) {
        if (initialized.compareAndSet(false, true)) {
            binding.initialize(context.getApplicationContext());
        }
    }

    private static native boolean initializeNative(Context applicationContext);
}
