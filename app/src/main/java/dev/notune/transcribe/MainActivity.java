package dev.notune.transcribe;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE = 101;
    private static final int REQ_VOICE_TEST = 202;
    private static final int REQ_FUTO_PAIRING = 1003;

    private String pendingBridgeCapability;
    private String pendingBridgePackage;

    static {
        try {
            System.loadLibrary("c++_shared");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load c++_shared", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private TextView statusText;
    private TextView voiceStatusText;
    private ImageView voiceStatusIcon;
    private Button voiceGrantButton;
    private Button voiceTryButton;
    private Button startSubsButton;
    private Button benchButton;
    private TextView benchResultText;
    private Button recognitionTestButton;
    private TextView recognitionTestStatus;
    private SpeechRecognizer testRecognizer;
    private boolean isTestingRecognition = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        voiceStatusText = findViewById(R.id.text_voice_status);
        voiceStatusIcon = findViewById(R.id.img_voice_status);
        voiceGrantButton = findViewById(R.id.btn_voice_grant);
        voiceTryButton = findViewById(R.id.btn_voice_try);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button voiceHelpButton = findViewById(R.id.btn_voice_help);
        recognitionTestButton = findViewById(R.id.btn_recognition_test);
        recognitionTestStatus = findViewById(R.id.text_recognition_test_status);

        voiceGrantButton.setOnClickListener(v -> checkAndRequestPermissions());
        voiceTryButton.setOnClickListener(v -> launchVoiceTest());
        voiceHelpButton.setOnClickListener(v -> showHelpDialog());
        if (recognitionTestButton != null) {
            recognitionTestButton.setOnClickListener(v -> {
                if (isTestingRecognition) {
                    stopRecognitionTest();
                } else {
                    startRecognitionTest();
                }
            });
        }

        imeSettingsButton.setOnClickListener(v -> {
             Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
             startActivity(intent);
        });

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        findViewById(R.id.btn_subs_advanced).setOnClickListener(v -> showSubsAdvancedDialog());

        findViewById(R.id.btn_models).setOnClickListener(v ->
                startActivity(new Intent(this, ModelsActivity.class)));

        benchButton = findViewById(R.id.btn_benchmark);
        benchResultText = findViewById(R.id.text_bench_result);
        benchButton.setOnClickListener(v -> runBenchmark());

        // Settings stored as marker files in filesDir (readable from the :ime
        // process and native code without a content provider).
        bindMarkerSwitch(R.id.switch_auto_record, "auto_record", false);
        bindMarkerSwitch(R.id.switch_select_transcription, "select_transcription", false);
        bindMarkerSwitch(R.id.switch_pause_audio, "pause_audio", false);
        // Record-in-background defaults to ON; its marker file is the opt-out.
        bindMarkerSwitch(R.id.switch_record_background, "stop_on_hide", true);
        bindMarkerSwitch(R.id.switch_auto_stop, "auto_stop", false);

        // Live subtitle line limit: 2 (default), 4, or 0 = unlimited.
        RadioGroup subsLinesGroup = findViewById(R.id.rg_subtitle_lines);
        int subsLines = SubtitlePrefs.getMaxLines(this);
        if (subsLines == 4) {
            subsLinesGroup.check(R.id.rb_subs_4);
        } else if (subsLines == 0) {
            subsLinesGroup.check(R.id.rb_subs_all);
        } else {
            subsLinesGroup.check(R.id.rb_subs_2);
        }
        subsLinesGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int lines = checkedId == R.id.rb_subs_4 ? 4
                    : checkedId == R.id.rb_subs_all ? 0 : 2;
            SubtitlePrefs.setMaxLines(this, lines);
        });

        RadioGroup themeGroup = findViewById(R.id.rg_theme);
        switch (ThemePrefs.getMode(this)) {
            case AppCompatDelegate.MODE_NIGHT_NO:
                themeGroup.check(R.id.rb_theme_light);
                break;
            case AppCompatDelegate.MODE_NIGHT_YES:
                themeGroup.check(R.id.rb_theme_dark);
                break;
            default:
                themeGroup.check(R.id.rb_theme_system);
                break;
        }
        themeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int newMode;
            if (checkedId == R.id.rb_theme_light) {
                newMode = AppCompatDelegate.MODE_NIGHT_NO;
            } else if (checkedId == R.id.rb_theme_dark) {
                newMode = AppCompatDelegate.MODE_NIGHT_YES;
            } else {
                newMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            }
            if (newMode != ThemePrefs.getMode(this)) {
                ThemePrefs.setMode(this, newMode);
            }
        });

        // Initial check
        updateVoiceInputStatus();
        updateBridgePairingStatus();

        Button bridgePairButton = findViewById(R.id.btn_bridge_pair);
        Button bridgeRevokeButton = findViewById(R.id.btn_bridge_revoke);
        if (bridgePairButton != null) bridgePairButton.setOnClickListener(v -> beginFutoPairing());
        if (bridgeRevokeButton != null) bridgeRevokeButton.setOnClickListener(v -> {
            BridgePairingStore.revoke(this);
            updateBridgePairingStatus();
        });

        // Start init
        initNative(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check on return from the keyboard chooser, settings, or a test run.
        updateVoiceInputStatus();
        updateBridgePairingStatus();
    }

    private void updateBridgePairingStatus() {
        TextView status = findViewById(R.id.text_bridge_status);
        Button pair = findViewById(R.id.btn_bridge_pair);
        Button revoke = findViewById(R.id.btn_bridge_revoke);
        if (status == null || pair == null || revoke == null) return;
        boolean paired = BridgePairingStore.isPaired(this);
        status.setText(paired ? R.string.bridge_paired : R.string.bridge_not_paired);
        pair.setVisibility(paired ? View.GONE : View.VISIBLE);
        revoke.setVisibility(paired ? View.VISIBLE : View.GONE);
    }

    private void beginFutoPairing() {
        String targetPackage = BridgePairingStore.resolveInstalledFutoPackage(this);
        Intent pairing = new Intent(BridgePairingStore.FUTO_PAIR_ACTION)
                .setComponent(new ComponentName(
                        targetPackage,
                        "org.futo.inputmethod.latin.uix.actions.OfflineVoiceBridgePairingActivity"));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.bridge_pairing_title)
                .setMessage(R.string.bridge_pairing_consent)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.bridge_pairing_continue, (d, w) -> {
                    pendingBridgeCapability = BridgePairingStore.createCapability();
                    pendingBridgePackage = targetPackage;
                    pairing.putExtra(BridgePairingStore.EXTRA_CAPABILITY, pendingBridgeCapability);
                    pairing.putExtra(Intent.EXTRA_REFERRER,
                            Uri.parse("android-app://" + getPackageName()));
                    try {
                        startActivityForResult(pairing, REQ_FUTO_PAIRING);
                    } catch (android.content.ActivityNotFoundException e) {
                        pendingBridgeCapability = null;
                        pendingBridgePackage = null;
                        snackbar(getString(R.string.bridge_pairing_unavailable));
                    }
                }).show();
    }

    /**
     * Reflects whether the primary voice-input flow is ready. Apps (SwiftKey,
     * Firefox, …) fire {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH} via
     * {@code startActivityForResult}, which {@link RecognizeActivity} handles.
     * The flow works when (a) the mic permission is granted and (b) our activity
     * is what Android resolves that intent to — either because it is the sole
     * handler or because the user picked us as the default. This is deliberately
     * judged on the activity path, NOT on being the default {@code RecognitionService}.
     */
    private void updateVoiceInputStatus() {
        boolean micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;

        if (!micGranted) {
            setVoiceStatus(false, getString(R.string.voice_status_need_mic));
            voiceGrantButton.setVisibility(View.VISIBLE);
            voiceTryButton.setEnabled(false);
            return;
        }

        voiceGrantButton.setVisibility(View.GONE);
        voiceTryButton.setEnabled(true);

        if (isOurAppDefaultRecognizer()) {
            setVoiceStatus(true, getString(R.string.voice_status_ready));
        } else {
            setVoiceStatus(false, getString(R.string.voice_status_almost));
        }
    }

    /** True if our RecognizeActivity is what RECOGNIZE_SPEECH resolves to. */
    private boolean isOurAppDefaultRecognizer() {
        Intent recog = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        ResolveInfo resolved = getPackageManager()
                .resolveActivity(recog, PackageManager.MATCH_DEFAULT_ONLY);
        return resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName);
    }

    private void setVoiceStatus(boolean ready, String message) {
        voiceStatusText.setText(message);
        voiceStatusIcon.setImageResource(ready ? R.drawable.ic_check_circle : R.drawable.ic_error);
        int tint = ready
                ? ContextCompat.getColor(this, R.color.status_ok)
                : themeColor(com.google.android.material.R.attr.colorError);
        ImageViewCompat.setImageTintList(voiceStatusIcon, ColorStateList.valueOf(tint));
        voiceStatusIcon.setContentDescription(message);
    }

    private int themeColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.resourceId != 0 ? ContextCompat.getColor(this, tv.resourceId) : tv.data;
    }

    /**
     * One-tap self-test: fires the exact intent a keyboard's mic does. If ours is
     * the sole handler it launches straight away; if several apps handle it the
     * system shows a chooser, where the user can pick us and "Always" — which sets
     * the default and flips the status to ready on return.
     */
    private void launchVoiceTest() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        try {
            startActivityForResult(intent, REQ_VOICE_TEST);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No RECOGNIZE_SPEECH handler", e);
            snackbar(getString(R.string.voice_test_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FUTO_PAIRING) {
            if (resultCode == RESULT_OK && data != null
                    && data.getBooleanExtra(BridgePairingStore.EXTRA_ACCEPTED, false)
                    && pendingBridgeCapability != null) {
                String pkgToSave = pendingBridgePackage != null
                        ? pendingBridgePackage
                        : BridgePairingStore.FUTO_PACKAGE;
                BridgePairingStore.save(this, pkgToSave, pendingBridgeCapability);
            }
            pendingBridgeCapability = null;
            pendingBridgePackage = null;
            updateBridgePairingStatus();
            return;
        }
        if (requestCode == REQ_VOICE_TEST && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            String heard = (results != null && !results.isEmpty()) ? results.get(0) : null;
            snackbar(heard != null && !heard.trim().isEmpty()
                    ? getString(R.string.voice_test_ok, heard)
                    : getString(R.string.voice_test_empty));
        }
        // onResume() also refreshes, but do it here too for an immediate update.
        updateVoiceInputStatus();
    }

    /**
     * Explains the adb escape hatch for the MediaProjection consent dialog:
     * once the PROJECT_MEDIA app-op is set to allow, the system permission
     * activity returns RESULT_OK immediately, so subtitles start without the
     * "Start recording or casting?" sheet. No app code depends on this — the
     * normal dialog flow is the untouched fallback.
     */
    private void showSubsAdvancedDialog() {
        String allowCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA allow";
        String resetCmd = "adb shell appops set --user 0 " + getPackageName()
                + " PROJECT_MEDIA default";
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.subs_advanced_title)
                .setMessage(getString(R.string.subs_advanced_body, allowCmd, resetCmd))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.subs_advanced_copy, (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", allowCmd));
                    snackbar(getString(R.string.subs_advanced_copied));
                })
                .show();
    }

    private void showHelpDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.voice_help_title)
                .setMessage(R.string.voice_help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void snackbar(String message) {
        Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show();
    }

    /**
     * Binds a switch to a marker file in filesDir. With {@code inverted}, the
     * file's presence means the switch is OFF (used for default-on settings).
     */
    private void bindMarkerSwitch(int switchId, String fileName, boolean inverted) {
        CompoundButton sw = findViewById(switchId);
        File marker = new File(getFilesDir(), fileName);
        sw.setChecked(marker.exists() != inverted);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean shouldExist = isChecked != inverted;
            if (shouldExist) {
                try {
                    marker.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create " + fileName + " file", e);
                }
            } else {
                marker.delete();
            }
        });
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updateVoiceInputStatus();
        }
    }

    // --- Benchmark ----------------------------------------------------------

    /**
     * Transcribes the bundled test clip through the active model and shows the
     * speed as a real-time factor. The clip is a fixed English recording so
     * results are comparable across models and devices; the run uses the
     * current model settings (language hint, translate).
     */
    private void runBenchmark() {
        benchButton.setEnabled(false);
        benchResultText.setVisibility(View.VISIBLE);
        benchResultText.setText(R.string.bench_running);

        new Thread(() -> {
            float[] samples;
            try {
                samples = readWavAsset("bench.wav");
            } catch (IOException e) {
                Log.e(TAG, "Failed to read benchmark clip", e);
                runOnUiThread(() -> {
                    benchResultText.setText(getString(R.string.bench_error, e.getMessage()));
                    benchButton.setEnabled(true);
                });
                return;
            }
            benchmarkNative(this, samples, samples.length);
        }, "benchmark-load").start();
    }

    // Called from Rust when the benchmark run finishes.
    public void onBenchmarkResult(float audioSecs, float computeSecs, String error) {
        runOnUiThread(() -> {
            benchButton.setEnabled(true);
            benchResultText.setVisibility(View.VISIBLE);
            if (error != null && !error.isEmpty()) {
                benchResultText.setText(getString(R.string.bench_error, error));
            } else {
                benchResultText.setText(getString(R.string.bench_result,
                        String.format(java.util.Locale.getDefault(), "%.1f", audioSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f", computeSecs),
                        String.format(java.util.Locale.getDefault(), "%.1f",
                                computeSecs > 0 ? audioSecs / computeSecs : 0)));
            }
        });
    }

    /**
     * Reads a 16 kHz mono 16-bit PCM WAV from assets into float samples. Only
     * handles the format the bundled clip is stored in; no general WAV support.
     */
    private float[] readWavAsset(String name) throws IOException {
        byte[] bytes;
        try (java.io.InputStream in = getAssets().open(name);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            bytes = out.toByteArray();
        }

        // Find the "data" chunk instead of assuming a 44-byte header.
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkSize = (bytes[offset + 4] & 0xff) | ((bytes[offset + 5] & 0xff) << 8)
                    | ((bytes[offset + 6] & 0xff) << 16) | ((bytes[offset + 7] & 0xff) << 24);
            if (bytes[offset] == 'd' && bytes[offset + 1] == 'a'
                    && bytes[offset + 2] == 't' && bytes[offset + 3] == 'a') {
                int start = offset + 8;
                int count = Math.min(chunkSize, bytes.length - start) / 2;
                float[] samples = new float[count];
                for (int i = 0; i < count; i++) {
                    int lo = bytes[start + 2 * i] & 0xff;
                    int hi = bytes[start + 2 * i + 1];
                    samples[i] = ((hi << 8) | lo) / 32768.0f;
                }
                return samples;
            }
            offset += 8 + chunkSize + (chunkSize & 1);
        }
        throw new IOException("no data chunk in " + name);
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText(status);
            // "Ready" may carry a suffix, e.g. "Ready (this model can't translate)".
            if (status.startsWith("Ready")) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    private void startRecognitionTest() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (recognitionTestStatus != null) {
                recognitionTestStatus.setText("Grant microphone permission first.");
            }
            return;
        }
        if (testRecognizer != null) {
            try { testRecognizer.destroy(); } catch (Throwable ignored) {}
            testRecognizer = null;
        }
        ComponentName comp = new ComponentName(this, VoiceRecognitionService.class);
        try {
            testRecognizer = SpeechRecognizer.createSpeechRecognizer(this, comp);
        } catch (Throwable t) {
            if (recognitionTestStatus != null) {
                recognitionTestStatus.setText("createSpeechRecognizer failed: " + t.getMessage());
            }
            return;
        }
        testRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                if (recognitionTestStatus != null) recognitionTestStatus.setText("Ready for speech (speak now)...");
            }
            @Override public void onBeginningOfSpeech() {
                if (recognitionTestStatus != null) recognitionTestStatus.setText("Beginning of speech detected.");
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                if (recognitionTestStatus != null) recognitionTestStatus.setText("End of speech. Processing...");
            }
            @Override public void onError(int error) {
                if (recognitionTestStatus != null) recognitionTestStatus.setText("Error: " + errorToString(error));
                finishRecognitionTest();
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String text = (matches != null && !matches.isEmpty())
                        ? matches.get(0) : "(empty)";
                if (recognitionTestStatus != null) recognitionTestStatus.setText("Final: " + text);
                finishRecognitionTest();
            }
            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                String text = (matches != null && !matches.isEmpty())
                        ? matches.get(0) : "";
                if (recognitionTestStatus != null) recognitionTestStatus.setText("Streaming: " + text);
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                          RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        try {
            testRecognizer.startListening(intent);
        } catch (Throwable t) {
            if (recognitionTestStatus != null) {
                recognitionTestStatus.setText("startListening failed: " + t.getMessage());
            }
            finishRecognitionTest();
            return;
        }
        isTestingRecognition = true;
        if (recognitionTestButton != null) {
            recognitionTestButton.setText(R.string.btn_recognition_test_stop);
        }
        if (recognitionTestStatus != null) {
            recognitionTestStatus.setText("Starting...");
        }
    }

    private void stopRecognitionTest() {
        if (testRecognizer != null) {
            try { testRecognizer.stopListening(); } catch (Throwable ignored) {}
        }
    }

    private void finishRecognitionTest() {
        isTestingRecognition = false;
        if (recognitionTestButton != null) {
            recognitionTestButton.setText(R.string.btn_recognition_test_start);
        }
        if (testRecognizer != null) {
            try { testRecognizer.destroy(); } catch (Throwable ignored) {}
            testRecognizer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (testRecognizer != null) {
            try { testRecognizer.destroy(); } catch (Throwable ignored) {}
            testRecognizer = null;
        }
    }

    private static String errorToString(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "AUDIO (3)";
            case SpeechRecognizer.ERROR_CLIENT: return "CLIENT (5)";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "INSUFFICIENT_PERMISSIONS (9)";
            case SpeechRecognizer.ERROR_NETWORK: return "NETWORK (2)";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "NETWORK_TIMEOUT (1)";
            case SpeechRecognizer.ERROR_NO_MATCH: return "NO_MATCH (7)";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "RECOGNIZER_BUSY (8)";
            case SpeechRecognizer.ERROR_SERVER: return "SERVER (4)";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "SPEECH_TIMEOUT (6)";
            default: return "code " + code;
        }
    }

    private native void initNative(MainActivity activity);

    private native void benchmarkNative(MainActivity activity, float[] samples, int length);
}
