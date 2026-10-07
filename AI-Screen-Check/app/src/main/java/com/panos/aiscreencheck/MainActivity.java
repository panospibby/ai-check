package com.panos.aiscreencheck;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;
    private static final int REQ_AUDIO = 1003;
    private static final int REQ_FILE = 2001;

    private EditText apiUser;
    private EditText apiSecret;
    private EditText copyleaksEmail;
    private EditText copyleaksKey;
    private TextView status;
    private MediaProjectionManager projectionManager;
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private boolean pendingStartAfterAudioPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        buildUi();
        requestNotificationPermissionIfNeeded();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(30));
        root.setBackgroundColor(0xFF101114);
        scroll.addView(root);

        TextView title = text("AI Screen Check 2.0", 28, 0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                "Floating AI detector για εικόνα, ορατό κείμενο, ήχο/μουσική και video frames. Ένα tap στο κυκλάκι = SMART scan. Παρατεταμένο tap = επιλογές.",
                15, 0xFFBEC2C9);
        subtitle.setPadding(0, dp(8), 0, dp(18));
        root.addView(subtitle);

        root.addView(section("Sightengine — εικόνα / video / μουσική / φωνή"));
        root.addView(label("API User"));
        apiUser = field("Sightengine API User");
        root.addView(apiUser);

        root.addView(label("API Secret"));
        apiSecret = passwordField("Sightengine API Secret");
        root.addView(apiSecret);

        root.addView(section("Copyleaks — AI κείμενο (προαιρετικό αλλά απαραίτητο για Text AI)"));
        root.addView(label("Email λογαριασμού Copyleaks"));
        copyleaksEmail = field("email@example.com");
        copyleaksEmail.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(copyleaksEmail);

        root.addView(label("Copyleaks API Key"));
        copyleaksKey = passwordField("API key");
        root.addView(copyleaksKey);

        Button overlay = button("1. Άδεια floating bubble");
        overlay.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlay);

        Button accessibility = button("2. Άδεια ανάγνωσης ορατού κειμένου");
        accessibility.setOnClickListener(v -> {
            Toast.makeText(this, "Ενεργοποίησε το AI Screen Check στις Υπηρεσίες προσβασιμότητας.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        root.addView(accessibility);

        Button start = button("3. Έναρξη floating AI Check");
        start.setOnClickListener(v -> startCaptureFlow());
        root.addView(start);

        Button analyzeFile = button("Ανάλυση αρχείου — image / video / audio / txt");
        analyzeFile.setOnClickListener(v -> pickFile());
        root.addView(analyzeFile);

        Button stop = button("Stop");
        stop.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, CaptureService.class);
            stopIntent.setAction(CaptureService.ACTION_STOP);
            startService(stopIntent);
            status.setText("Σταματημένο.");
        });
        root.addView(stop);

        status = text("Κατάσταση: ανενεργό", 14, 0xFF8AB4F8);
        status.setPadding(0, dp(16), 0, dp(10));
        root.addView(status);

        TextView help = text(
                "Χρήση: πάτησε μία φορά το μπλε AI κυκλάκι για SMART scan. Κράτησέ το πατημένο ~0,7s για IMAGE / TEXT / AUDIO / VIDEO. Μπορείς επίσης να το σύρεις όπου θέλεις.\n\n" +
                "Το live VIDEO mode κάνει δειγματοληψία πολλών screen frames. Για πραγματική ανάλυση του ίδιου του video file χρησιμοποίησε «Ανάλυση αρχείου».\n\n" +
                "Privacy: screenshots, OCR text και audio samples αποστέλλονται μόνο στους detectors όταν πατήσεις scan. Δεν αποθηκεύονται μόνιμα από το app. Μην κάνεις scan όταν φαίνονται κωδικοί, τραπεζικά στοιχεία ή ιδιωτικές συνομιλίες.",
                13, 0xFF9BA0A8);
        help.setPadding(0, dp(12), 0, 0);
        root.addView(help);

        setContentView(scroll);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Η άδεια floating overlay είναι ήδη ενεργή.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startCaptureFlow() {
        String user = apiUser.getText().toString().trim();
        String secret = apiSecret.getText().toString().trim();
        if (user.isEmpty() || secret.isEmpty()) {
            Toast.makeText(this, "Βάλε Sightengine API User και API Secret.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Πρώτα ενεργοποίησε την άδεια floating overlay.", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStartAfterAudioPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        requestScreenCapture();
    }

    private void requestScreenCapture() {
        Intent captureIntent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(captureIntent, REQ_CAPTURE);
        status.setText("Περιμένω άδεια Screen Capture…");
    }

    private void pickFile() {
        boolean hasSightengine = !apiUser.getText().toString().trim().isEmpty() && !apiSecret.getText().toString().trim().isEmpty();
        boolean hasCopyleaks = !copyleaksEmail.getText().toString().trim().isEmpty() && !copyleaksKey.getText().toString().trim().isEmpty();
        if (!hasSightengine && !hasCopyleaks) {
            Toast.makeText(this, "Βάλε Sightengine credentials για media ή Copyleaks credentials για text.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*", "text/plain"});
        startActivityForResult(intent, REQ_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                status.setText("Η άδεια Screen Capture ακυρώθηκε.");
                return;
            }
            Intent service = new Intent(this, CaptureService.class);
            service.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            service.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            service.putExtra(CaptureService.EXTRA_API_USER, apiUser.getText().toString().trim());
            service.putExtra(CaptureService.EXTRA_API_SECRET, apiSecret.getText().toString().trim());
            service.putExtra(CaptureService.EXTRA_COPYLEAKS_EMAIL, copyleaksEmail.getText().toString().trim());
            service.putExtra(CaptureService.EXTRA_COPYLEAKS_KEY, copyleaksKey.getText().toString().trim());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            status.setText("Ενεργό. Άνοιξε TikTok / Instagram / browser και πάτησε το AI κυκλάκι.");
            return;
        }

        if (requestCode == REQ_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            analyzePickedFile(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO && pendingStartAfterAudioPermission) {
            pendingStartAfterAudioPermission = false;
            requestScreenCapture();
        }
    }

    private void analyzePickedFile(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime == null) mime = "application/octet-stream";
        final String finalMime = mime;
        final String seUser = apiUser.getText().toString().trim();
        final String seSecret = apiSecret.getText().toString().trim();
        final String clEmail = copyleaksEmail.getText().toString().trim();
        final String clKey = copyleaksKey.getText().toString().trim();
        status.setText("Ανάλυση αρχείου…");

        fileExecutor.execute(() -> {
            File temp = null;
            try {
                AiApiClient ai = new AiApiClient(seUser, seSecret);
                CopyleaksClient textAi = new CopyleaksClient(clEmail, clKey);
                String result;

                if (finalMime.startsWith("text/")) {
                    String txt = readUriText(uri);
                    if (!textAi.isConfigured()) throw new IllegalStateException("Βάλε Copyleaks email + API key για AI text detection");
                    CopyleaksClient.TextResult r = textAi.detectText(txt);
                    if (r.tooShort) result = "Text: πολύ μικρό για detector (" + r.chars + "/255 χαρακτήρες)";
                    else result = "Text: " + scoreLabel(r.aiScore, "AI", "human");
                } else if (finalMime.startsWith("image/")) {
                    if (!ai.isConfigured()) throw new IllegalStateException("Βάλε Sightengine API User + Secret για media detection");
                    byte[] bytes = readUriBytes(uri, 25 * 1024 * 1024);
                    AiApiClient.ScoreResult r = ai.analyzeImage(bytes, "upload" + extensionForImageMime(finalMime), finalMime);
                    result = "Image: " + scoreLabel(r.aiScore, "AI", "real") + (r.generator.isEmpty() ? "" : " • " + r.generator);
                } else if (finalMime.startsWith("audio/")) {
                    if (!ai.isConfigured()) throw new IllegalStateException("Βάλε Sightengine API User + Secret για media detection");
                    temp = copyToCache(uri, extensionForMime(finalMime, ".audio"), 80 * 1024 * 1024);
                    AiApiClient.AudioResult r = ai.analyzeAudioFile(temp);
                    result = formatAudioFileResult(r);
                } else if (finalMime.startsWith("video/")) {
                    if (!ai.isConfigured()) throw new IllegalStateException("Βάλε Sightengine API User + Secret για media detection");
                    long duration = getDurationMs(uri);
                    if (duration > 60_000L) throw new IllegalStateException("Το direct video scan είναι για video έως 60 δευτερόλεπτα");
                    temp = copyToCache(uri, extensionForMime(finalMime, ".mp4"), 80 * 1024 * 1024);
                    AiApiClient.VideoResult r = ai.analyzeVideo(temp, finalMime);
                    result = String.format(Locale.US, "Video: AI %.0f%% avg • max %.0f%% • %d frames", r.averageAi * 100.0, r.maxAi * 100.0, r.frames);
                } else {
                    throw new IllegalStateException("Μη υποστηριζόμενος τύπος αρχείου: " + finalMime);
                }
                final String ok = result;
                runOnUiThread(() -> status.setText(ok));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> status.setText("File scan error: " + msg));
            } finally {
                if (temp != null) temp.delete();
            }
        });
    }

    private String formatAudioFileResult(AiApiClient.AudioResult r) {
        StringBuilder sb = new StringBuilder("Audio: ");
        if (!Double.isNaN(r.musicAi)) sb.append("Music ").append(scoreLabel(r.musicAi, "AI", "real"));
        if (!Double.isNaN(r.speechAi)) {
            if (sb.length() > 7) sb.append(" • ");
            sb.append("Voice ").append(scoreLabel(r.speechAi, "AI", "real"));
        } else if (r.speechUnavailable) {
            sb.append(" • voice model not enabled on this Sightengine account");
        }
        return sb.toString();
    }

    private static String scoreLabel(double ai, String aiWord, String realWord) {
        if (ai >= 0.70) return String.format(Locale.US, "%s %.0f%%", aiWord, ai * 100.0);
        if (ai <= 0.30) return String.format(Locale.US, "%s %.0f%%", realWord, (1.0 - ai) * 100.0);
        return String.format(Locale.US, "uncertain • AI %.0f%%", ai * 100.0);
    }

    private File copyToCache(Uri uri, String extension, long maxBytes) throws Exception {
        File outFile = File.createTempFile("ai_check_", extension, getCacheDir());
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(outFile)) {
            if (in == null) throw new IllegalStateException("Δεν μπορώ να ανοίξω το αρχείο");
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IllegalStateException("Το αρχείο είναι πολύ μεγάλο για direct scan");
                out.write(buffer, 0, n);
            }
        }
        return outFile;
    }

    private byte[] readUriBytes(Uri uri, long maxBytes) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("Δεν μπορώ να ανοίξω το αρχείο");
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IllegalStateException("Το αρχείο είναι πολύ μεγάλο");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String readUriText(Uri uri) throws Exception {
        byte[] bytes = readUriBytes(uri, 3 * 1024 * 1024);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private long getDurationMs(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1L : Long.parseLong(d);
        } catch (Exception e) {
            return -1L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private String extensionForImageMime(String mime) {
        if (mime == null) return ".jpg";
        if (mime.contains("png")) return ".png";
        if (mime.contains("webp")) return ".webp";
        if (mime.contains("gif")) return ".gif";
        if (mime.contains("heic") || mime.contains("heif")) return ".heic";
        return ".jpg";
    }

    private String extensionForMime(String mime, String fallback) {
        if (mime == null) return fallback;
        String m = mime.toLowerCase(Locale.US);
        if (m.startsWith("audio/") && (m.contains("mp4") || m.contains("m4a"))) return ".m4a";
        if (m.contains("quicktime")) return ".mov";
        if (m.contains("matroska")) return ".mkv";
        if (m.contains("msvideo") || m.contains("avi")) return ".avi";
        if (m.contains("mp4")) return ".mp4";
        if (m.contains("mpeg")) return ".mp3";
        if (m.contains("wav")) return ".wav";
        if (m.contains("ogg")) return ".ogg";
        if (m.contains("webm")) return ".webm";
        if (m.contains("flac")) return ".flac";
        return fallback;
    }

    private TextView section(String s) {
        TextView v = text(s, 15, 0xFFFFFFFF);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }

    private TextView label(String s) {
        TextView v = text(s, 13, 0xFFD2D6DC);
        v.setPadding(0, dp(10), 0, dp(6));
        return v;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0xFF6E737B);
        e.setTextColor(0xFFFFFFFF);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(0xFF25272C);
        return e;
    }

    private EditText passwordField(String hint) {
        EditText e = field(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        return e;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private TextView text(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setGravity(Gravity.START);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        fileExecutor.shutdownNow();
        super.onDestroy();
    }
}
