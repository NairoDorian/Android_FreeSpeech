package dev.notune.transcribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Base64;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/** Authenticated, on-demand endpoint for the paired FUTO IME. */
public final class OfflineVoiceBridgeService extends Service {
    public static final int STATE_STARTING = 1;
    public static final int STATE_LISTENING = 2;
    public static final int STATE_PROCESSING = 3;
    public static final int ERROR_UNAVAILABLE = 1;
    public static final int ERROR_MIC_PERMISSION = 2;
    public static final int ERROR_CAPTURE = 3;
    public static final int ERROR_NO_MATCH = 4;
    public static final int ERROR_TIMEOUT = 5;

    private static final String CHANNEL_ID = "OfflineVoiceBridge";
    private static final int NOTIFICATION_ID = 23457;
    private static final long MAX_SESSION_MS = 60_000L;
    private static final long FOREGROUND_TOKEN_TTL_MS = 10_000L;
    static final String ACTION_FOREGROUND_START =
            "dev.notune.transcribe.action.OFFLINE_VOICE_BRIDGE_FOREGROUND_START";
    static final String EXTRA_FOREGROUND_NONCE =
            "dev.notune.transcribe.extra.OFFLINE_VOICE_BRIDGE_FOREGROUND_NONCE";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private IOfflineVoiceBridgeCallback callback;
    private IBinder callbackBinder;
    private boolean active;
    private boolean nativeInitialized;
    private volatile boolean foregroundReady;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, Long> foregroundNonces = new HashMap<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener pairingChanged =
            (preferences, key) -> {
                if (active && !BridgePairingStore.isPaired(this)) handler.post(this::cleanup);
            };

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("android_transcribe_app");
    }

    private final IBinder.DeathRecipient callbackDied = () -> handler.post(this::cleanup);
    private final Runnable timeout = () -> {
        if (active) {
            deliverError(ERROR_TIMEOUT, getString(R.string.bridge_error_timeout));
            cleanup();
        }
    };

    private final IOfflineVoiceBridge.Stub binder = new IOfflineVoiceBridge.Stub() {
        @Override public String pair() {
            String callingPkg = getCallingPairedPackage();
            if (callingPkg == null) throw new SecurityException("Unsupported caller");
            String capability = BridgePairingStore.createCapability();
            BridgePairingStore.save(OfflineVoiceBridgeService.this, callingPkg, capability);
            return capability;
        }
        @Override public PendingIntent requestForegroundStart(String capability) {
            requireAuthorized(capability);
            foregroundReady = startBridgeForeground();
            return null;
        }
        @Override public boolean isForegroundReady(String capability) {
            requireAuthorized(capability);
            return foregroundReady;
        }
        @Override public void start(String capability, IOfflineVoiceBridgeCallback newCallback) {
            requireAuthorized(capability);
            if (newCallback == null) throw new IllegalArgumentException("callback required");
            handler.post(() -> startSession(newCallback));
        }
        @Override public void stop(String capability) {
            requireAuthorized(capability);
            handler.post(() -> stopSession());
        }
        @Override public void cancel(String capability) {
            requireAuthorized(capability);
            handler.post(OfflineVoiceBridgeService.this::cleanup);
        }
        @Override public boolean isPaired(String capability) {
            return isAuthorized(capability);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.bridge_channel_name), NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
        BridgePairingStore.preferences(this)
                .registerOnSharedPreferenceChangeListener(pairingChanged);
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    static Intent foregroundStartIntent(String nonce) {
        return new Intent()
                .setClassName(OfflineVoiceBridgeService.class.getPackage().getName(),
                        OfflineVoiceBridgeService.class.getName())
                .setAction(ACTION_FOREGROUND_START)
                .putExtra(EXTRA_FOREGROUND_NONCE, nonce);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_FOREGROUND_START.equals(intent.getAction())
                && consumeForegroundNonce(intent.getStringExtra(EXTRA_FOREGROUND_NONCE))) {
            foregroundReady = startBridgeForeground();
        }
        return START_NOT_STICKY;
    }

    private boolean consumeForegroundNonce(String nonce) {
        if (nonce == null) return false;
        synchronized (foregroundNonces) {
            Long expiresAt = foregroundNonces.remove(nonce);
            return expiresAt != null && expiresAt >= System.currentTimeMillis();
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        handler.post(this::cleanup);
        return false;
    }

    @Override public void onDestroy() {
        BridgePairingStore.preferences(this)
                .unregisterOnSharedPreferenceChangeListener(pairingChanged);
        cleanup();
        super.onDestroy();
    }

    private void requireAuthorized(String capability) {
        if (!isAuthorized(capability)) throw new SecurityException("Not paired");
    }

    private String getCallingPairedPackage() {
        String[] packages = getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return null;
        for (String packageName : packages) {
            if (packageName != null && packageName.startsWith("org.futo.inputmethod.latin")) {
                return packageName;
            }
        }
        return null;
    }

    private boolean isAuthorized(String capability) {
        int uid = Binder.getCallingUid();
        String[] packages = getPackageManager().getPackagesForUid(uid);
        String pairedPackage = BridgePairingStore.packageName(this);
        if (packages == null) return false;
        for (String packageName : packages) {
            if (BridgeAuthorization.isAuthorized(pairedPackage,
                    BridgePairingStore.capability(this), packageName, capability)) return true;
        }
        return false;
    }

    private void startSession(IOfflineVoiceBridgeCallback newCallback) {
        if (active) {
            try { newCallback.onError(ERROR_UNAVAILABLE, getString(R.string.bridge_error_busy)); }
            catch (RemoteException ignored) {}
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callback = newCallback;
            deliverError(ERROR_MIC_PERMISSION, getString(R.string.bridge_error_mic_permission));
            callback = null;
            return;
        }
        callback = newCallback;
        callbackBinder = newCallback.asBinder();
        try {
            callbackBinder.linkToDeath(callbackDied, 0);
        } catch (RemoteException e) {
            cleanup();
            return;
        }
        active = true;
        if (!foregroundReady) {
            foregroundReady = startBridgeForeground();
        }
        notifyState(STATE_STARTING);
        if (!nativeInitialized) {
            initNative(this);
            nativeInitialized = true;
        }
        startRecordingNative();
        handler.postDelayed(timeout, MAX_SESSION_MS);
    }

    private void stopSession() {
        if (!active) return;
        handler.removeCallbacks(timeout);
        notifyState(STATE_PROCESSING);
        stopRecordingNative();
    }

    // Native callbacks may arrive on a worker thread.
    public void onStatusUpdate(String status) {
        handler.post(() -> {
            if (!active) return;
            if ("Listening...".equals(status)) notifyState(STATE_LISTENING);
            else if ("Transcribing...".equals(status)) notifyState(STATE_PROCESSING);
            else if (status != null && status.startsWith("Error")) {
                deliverError(ERROR_CAPTURE, getString(R.string.bridge_error_capture));
                cleanup();
            }
        });
    }

    public void onPartialText(String committed, String tentative) {
        handler.post(() -> {
            if (!active || callback == null) return;
            StringBuilder sb = new StringBuilder();
            if (committed != null && !committed.isEmpty()) sb.append(committed);
            if (tentative != null && !tentative.isEmpty()) {
                if (sb.length() > 0 && !sb.toString().endsWith(" ") && !tentative.startsWith(" ")) {
                    sb.append(" ");
                }
                sb.append(tentative);
            }
            String combined = sb.toString().trim();
            if (!combined.isEmpty()) {
                try {
                    callback.onPartialResult(combined);
                } catch (RemoteException ignored) {}
            }
        });
    }

    public void onPartialText(String text) {
        onPartialText("", text);
    }

    public void onAutoStop() {
        handler.post(() -> {
            if (!active) return;
            stopSession();
        });
    }

    public void onTextTranscribed(String text) {
        handler.post(() -> {
            if (!active) return;
            if (text == null || text.trim().isEmpty()) {
                deliverError(ERROR_NO_MATCH, getString(R.string.bridge_error_no_match));
            } else {
                try { callback.onResult(text); } catch (RemoteException ignored) {}
            }
            cleanup();
        });
    }

    public void onAudioLevel(float level) { }

    private void notifyState(int state) {
        try { if (callback != null) callback.onState(state); } catch (RemoteException e) { cleanup(); }
    }

    private void deliverError(int code, String message) {
        try { if (callback != null) callback.onError(code, message); } catch (RemoteException ignored) {}
    }

    /**
     * Android can reject microphone foreground work when this app is backgrounded.
     * Return a precise bridge error rather than leaking an uncaught service failure
     * back to the IME as an unrelated microphone-permission message.
     */
    private boolean startBridgeForeground() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.bridge_notification_title))
                .setContentText(getString(R.string.bridge_notification_text))
                .setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else startForeground(NOTIFICATION_ID, notification);
            return true;
        } catch (RuntimeException ignored) {
            // Includes ForegroundServiceStartNotAllowedException on Android 12+.
            // The Binder client receives a recoverable foreground-start error instead
            // of allowing the service process to terminate.
            return false;
        }
    }

    private void cleanup() {
        handler.removeCallbacks(timeout);
        if (nativeInitialized) cancelRecordingNative();
        if (callbackBinder != null) callbackBinder.unlinkToDeath(callbackDied, 0);
        callback = null;
        callbackBinder = null;
        active = false;
        foregroundReady = false;
        synchronized (foregroundNonces) { foregroundNonces.clear(); }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (nativeInitialized) {
            unloadNative();
            nativeInitialized = false;
        }
        stopSelf();
    }

    private native void initNative(OfflineVoiceBridgeService service);
    private native void startRecordingNative();
    private native void stopRecordingNative();
    private native void cancelRecordingNative();
    private native void unloadNative();
}
