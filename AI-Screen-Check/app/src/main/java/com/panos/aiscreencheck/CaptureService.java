package com.panos.aiscreencheck;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureService extends Service {
    public static final String ACTION_STOP = "com.panos.aiscreencheck.STOP";
    public static final String ACTION_SMART_SCAN = "com.panos.aiscreencheck.SMART_SCAN";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_API_USER = "apiUser";
    public static final String EXTRA_API_SECRET = "apiSecret";
    public static final String EXTRA_COPYLEAKS_EMAIL = "copyleaksEmail";
    public static final String EXTRA_COPYLEAKS_KEY = "copyleaksKey";

    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "ai_screen_check";
    private static final int AUDIO_SECONDS = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newFixedThreadPool(3);
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
    private final ConcurrentLinkedQueue<FrameTask> frameTasks = new ConcurrentLinkedQueue<>();

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private TextRecognizer textRecognizer;

    private AiApiClient aiClient;
    private CopyleaksClient textClient;

    private WindowManager windowManager;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleParams;
    private TextView resultCard;
    private LinearLayout menu;

    private volatile ScanSession activeSession;
    private volatile boolean shuttingDown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SMART_SCAN.equals(intent.getAction())) {
            if (mediaProjection != null && !shuttingDown) startSmartScan();
            return START_NOT_STICKY;
        }
        if (mediaProjection != null) return START_NOT_STICKY;
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String apiUser = intent.getStringExtra(EXTRA_API_USER);
        String apiSecret = intent.getStringExtra(EXTRA_API_SECRET);
        String copyleaksEmail = intent.getStringExtra(EXTRA_COPYLEAKS_EMAIL);
        String copyleaksKey = intent.getStringExtra(EXTRA_COPYLEAKS_KEY);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = parcelableIntent(intent, EXTRA_RESULT_DATA);

        aiClient = new AiApiClient(apiUser, apiSecret);
        textClient = new CopyleaksClient(copyleaksEmail, copyleaksKey);

        if (!aiClient.isConfigured() || resultData == null || resultCode == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification("Floating AI Check ενεργό");
        startProjectionForeground(notification);

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            stopEverything();
            return START_NOT_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopEverything();
            }
        }, mainHandler);

        startCapturePipeline();
        showBubble();
        return START_NOT_STICKY;
    }

    private void startProjectionForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private void startCapturePipeline() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sourceW = Math.max(1, dm.widthPixels);
        int sourceH = Math.max(1, dm.heightPixels);
        int captureW = Math.min(900, sourceW);
        int captureH = Math.max(1, Math.round(sourceH * (captureW / (float) sourceW)));
        int density = dm.densityDpi;

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 3);
        captureThread = new HandlerThread("ai-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null || shuttingDown) return;

                FrameTask task = nextActiveTask();
                if (task == null) return;

                Bitmap bitmap = imageToBitmap(image);
                if (bitmap == null) {
                    updateLine(task.session, task.lineKey(), "capture failed");
                    return;
                }
                processFrameTask(task, bitmap);
            } catch (Exception e) {
                ScanSession s = activeSession;
                if (s != null) updateLine(s, "Visual", "capture error");
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "AI-Screen-Check",
                captureW,
                captureH,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
    }

    private FrameTask nextActiveTask() {
        FrameTask task;
        while ((task = frameTasks.poll()) != null) {
            if (task.session == activeSession && !task.session.cancelled) return task;
        }
        return null;
    }

    private void processFrameTask(FrameTask task, Bitmap bitmap) {
        switch (task.kind) {
            case IMAGE:
                handleImageFrame(task.session, bitmap);
                break;
            case TEXT:
                handleTextFrame(task.session, bitmap);
                break;
            case SMART:
                handleSmartFrame(task.session, bitmap);
                break;
            case VIDEO:
                handleVideoFrame(task, bitmap);
                break;
        }
    }

    private void handleImageFrame(ScanSession session, Bitmap bitmap) {
        byte[] jpeg = jpeg(bitmap);
        bitmap.recycle();
        networkExecutor.execute(() -> {
            try {
                AiApiClient.ScoreResult r = aiClient.analyzeImage(jpeg);
                updateLine(session, "Image", visualScore(r));
            } catch (Exception e) {
                updateLine(session, "Image", "error: " + shortMessage(e));
            }
        });
    }

    private void handleSmartFrame(ScanSession session, Bitmap bitmap) {
        byte[] jpeg = jpeg(bitmap);
        networkExecutor.execute(() -> {
            try {
                AiApiClient.ScoreResult r = aiClient.analyzeImage(jpeg);
                updateLine(session, "Visual", visualScore(r));
            } catch (Exception e) {
                updateLine(session, "Visual", "error: " + shortMessage(e));
            }
        });

        VisibleTextStore.Snapshot snapshot = VisibleTextStore.get();
        String base = snapshot.isFresh(12000L) ? snapshot.text : "";
        if (!textClient.isConfigured()) {
            updateLine(session, "Text", "Copyleaks not configured");
            bitmap.recycle();
        } else if (base.trim().length() >= 255) {
            bitmap.recycle();
            submitText(session, base);
        } else {
            recognizeText(session, bitmap, base);
        }
    }

    private void handleTextFrame(ScanSession session, Bitmap bitmap) {
        VisibleTextStore.Snapshot snapshot = VisibleTextStore.get();
        String base = snapshot.isFresh(12000L) ? snapshot.text : "";
        if (!textClient.isConfigured()) {
            updateLine(session, "Text", "Copyleaks credentials missing");
            bitmap.recycle();
            return;
        }
        if (base.trim().length() >= 255) {
            bitmap.recycle();
            submitText(session, base);
            return;
        }
        recognizeText(session, bitmap, base);
    }

    private void recognizeText(ScanSession session, Bitmap bitmap, String baseText) {
        InputImage input = InputImage.fromBitmap(bitmap, 0);
        textRecognizer.process(input)
                .addOnSuccessListener(text -> {
                    String ocr = text == null ? "" : text.getText();
                    String combined = mergeText(baseText, ocr);
                    bitmap.recycle();
                    if (combined.trim().isEmpty()) {
                        updateLine(session, "Text", "no readable text found");
                    } else {
                        submitText(session, combined);
                    }
                })
                .addOnFailureListener(e -> {
                    bitmap.recycle();
                    if (baseText != null && !baseText.trim().isEmpty()) {
                        submitText(session, baseText);
                    } else {
                        updateLine(session, "Text", "OCR failed");
                    }
                });
    }

    private void submitText(ScanSession session, String text) {
        updateLine(session, "Text", "checking " + text.length() + " chars…");
        networkExecutor.execute(() -> {
            try {
                CopyleaksClient.TextResult r = textClient.detectText(text);
                if (r.tooShort) {
                    updateLine(session, "Text", "too short • " + r.chars + "/255 chars");
                } else {
                    updateLine(session, "Text", classification(r.aiScore, "AI text", "human text"));
                }
            } catch (Exception e) {
                updateLine(session, "Text", "error: " + shortMessage(e));
            }
        });
    }

    private void handleVideoFrame(FrameTask task, Bitmap bitmap) {
        byte[] jpeg = jpeg(bitmap);
        bitmap.recycle();
        networkExecutor.execute(() -> {
            try {
                AiApiClient.ScoreResult r = aiClient.analyzeImage(jpeg);
                task.session.addVideoScore(r.aiScore, task.total);
            } catch (Exception e) {
                task.session.addVideoError(task.total);
            }
            renderSession(task.session);
        });
    }

    private void startSmartScan() {
        if (activeSession != null) activeSession.cancelled = true;
        ScanSession s = new ScanSession("SMART SCAN");
        s.lines.put("Visual", "waiting for screen…");
        s.lines.put("Text", textClient != null && textClient.isConfigured() ? "reading screen…" : "Copyleaks not configured");
        s.lines.put("Audio", Build.VERSION.SDK_INT >= 29 ? "recording 8s…" : "requires Android 10+");
        activeSession = s;
        renderSession(s);
        enqueueFrame(new FrameTask(FrameKind.SMART, s, 1, 1));
        startAudioScan(s);
    }

    private void startImageScan() {
        if (activeSession != null) activeSession.cancelled = true;
        ScanSession s = new ScanSession("IMAGE CHECK");
        s.lines.put("Image", "waiting for screen…");
        activeSession = s;
        renderSession(s);
        enqueueFrame(new FrameTask(FrameKind.IMAGE, s, 1, 1));
    }

    private void startTextScan() {
        if (activeSession != null) activeSession.cancelled = true;
        ScanSession s = new ScanSession("TEXT CHECK");
        s.lines.put("Text", "reading screen…");
        activeSession = s;
        renderSession(s);

        VisibleTextStore.Snapshot snapshot = VisibleTextStore.get();
        if (textClient == null || !textClient.isConfigured()) {
            updateLine(s, "Text", "Copyleaks credentials missing");
        } else if (snapshot.isFresh(12000L) && snapshot.text.trim().length() >= 255) {
            submitText(s, snapshot.text);
        } else {
            enqueueFrame(new FrameTask(FrameKind.TEXT, s, 1, 1));
        }
    }

    private void startAudioOnlyScan() {
        if (activeSession != null) activeSession.cancelled = true;
        ScanSession s = new ScanSession("AUDIO / MUSIC CHECK");
        s.lines.put("Audio", "recording 8s…");
        activeSession = s;
        renderSession(s);
        startAudioScan(s);
    }

    private void startVideoScan() {
        if (activeSession != null) activeSession.cancelled = true;
        ScanSession s = new ScanSession("LIVE VIDEO CHECK");
        s.lines.put("Video", "sampling 5 screen frames…");
        activeSession = s;
        renderSession(s);
        for (int i = 0; i < 5; i++) {
            final int index = i + 1;
            mainHandler.postDelayed(() -> enqueueFrame(new FrameTask(FrameKind.VIDEO, s, index, 5)), i * 900L);
        }
    }

    private void startAudioScan(ScanSession session) {
        if (Build.VERSION.SDK_INT < 29) {
            updateLine(session, "Audio", "requires Android 10+");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateLine(session, "Audio", "RECORD_AUDIO permission missing");
            return;
        }

        audioExecutor.execute(() -> {
            AudioRecord record = null;
            try {
                int sampleRate = 44100;
                int channelMask = AudioFormat.CHANNEL_IN_MONO;
                int encoding = AudioFormat.ENCODING_PCM_16BIT;
                int min = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding);
                if (min <= 0) min = 8192;

                AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();

                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build();

                record = new AudioRecord.Builder()
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(min * 2, 16384))
                        .setAudioPlaybackCaptureConfig(config)
                        .build();

                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    updateLine(session, "Audio", "audio capture unavailable");
                    return;
                }

                record.startRecording();
                ByteArrayOutputStream raw = new ByteArrayOutputStream(sampleRate * 2 * AUDIO_SECONDS);
                byte[] buffer = new byte[Math.max(min, 4096)];
                long end = System.currentTimeMillis() + AUDIO_SECONDS * 1000L;
                int maxAbs = 0;
                while (System.currentTimeMillis() < end && session == activeSession && !session.cancelled) {
                    int n = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                    if (n > 0) {
                        raw.write(buffer, 0, n);
                        maxAbs = Math.max(maxAbs, maxPcm16(buffer, n));
                    }
                }
                record.stop();

                if (session != activeSession || session.cancelled) return;
                if (raw.size() < sampleRate || maxAbs < 120) {
                    updateLine(session, "Audio", "no capturable audio • source may block capture");
                    return;
                }

                updateLine(session, "Audio", "analyzing sample…");
                byte[] wav = makeWav(raw.toByteArray(), sampleRate, 1, 16);
                AiApiClient.AudioResult r = aiClient.analyzeAudio(wav);
                updateLine(session, "Audio", formatAudioResult(r));
            } catch (SecurityException se) {
                updateLine(session, "Audio", "capture blocked by Android/source app");
            } catch (Exception e) {
                updateLine(session, "Audio", "error: " + shortMessage(e));
            } finally {
                if (record != null) {
                    try { record.release(); } catch (Exception ignored) {}
                }
            }
        });
    }

    private String formatAudioResult(AiApiClient.AudioResult r) {
        StringBuilder sb = new StringBuilder();
        if (!Double.isNaN(r.musicAi)) {
            sb.append("Music ").append(classificationShort(r.musicAi));
        }
        if (!Double.isNaN(r.speechAi)) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("Voice ").append(classificationShort(r.speechAi));
        } else if (r.speechUnavailable) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("voice model not enabled");
        }
        return sb.length() == 0 ? "no audio score" : sb.toString();
    }

    private void enqueueFrame(FrameTask task) {
        if (task.session != activeSession || task.session.cancelled) return;
        frameTasks.offer(task);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int width = image.getWidth();
        int height = image.getHeight();
        int rowPadding = rowStride - pixelStride * width;
        int paddedWidth = width + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private byte[] jpeg(Bitmap bitmap) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 84, out);
        return out.toByteArray();
    }

    private void showBubble() {
        mainHandler.post(() -> {
            if (shuttingDown || bubble != null) return;
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            bubble = new TextView(this);
            bubble.setText("AI");
            bubble.setTextColor(Color.WHITE);
            bubble.setTextSize(16);
            bubble.setTypeface(null, android.graphics.Typeface.BOLD);
            bubble.setGravity(Gravity.CENTER);
            bubble.setBackground(circleBackground(0xFF3E64FF));

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            DisplayMetrics dm = getResources().getDisplayMetrics();
            bubbleParams = new WindowManager.LayoutParams(
                    dp(58), dp(58), type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT
            );
            bubbleParams.gravity = Gravity.TOP | Gravity.START;
            bubbleParams.x = Math.max(dp(8), dm.widthPixels - dp(76));
            bubbleParams.y = dp(190);

            attachBubbleTouch();
            try {
                windowManager.addView(bubble, bubbleParams);
            } catch (Exception e) {
                bubble = null;
            }
        });
    }

    private void attachBubbleTouch() {
        if (bubble == null) return;
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY;
            int startX, startY;
            long downAt;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = bubbleParams.x;
                        startY = bubbleParams.y;
                        downAt = System.currentTimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) moved = true;
                        if (moved && windowManager != null && bubble != null) {
                            bubbleParams.x = startX + Math.round(dx);
                            bubbleParams.y = startY + Math.round(dy);
                            try { windowManager.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            long held = System.currentTimeMillis() - downAt;
                            if (held >= 650L) toggleMenu();
                            else startSmartScan();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void toggleMenu() {
        mainHandler.post(() -> {
            if (menu != null) {
                hideMenu();
                return;
            }
            if (windowManager == null) return;
            menu = new LinearLayout(this);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setPadding(dp(8), dp(8), dp(8), dp(8));
            menu.setBackground(roundedBackground(0xF0202228, dp(18)));
            menu.addView(menuItem("SMART", v -> { hideMenu(); startSmartScan(); }));
            menu.addView(menuItem("IMAGE", v -> { hideMenu(); startImageScan(); }));
            menu.addView(menuItem("TEXT", v -> { hideMenu(); startTextScan(); }));
            menu.addView(menuItem("AUDIO", v -> { hideMenu(); startAudioOnlyScan(); }));
            menu.addView(menuItem("VIDEO", v -> { hideMenu(); startVideoScan(); }));
            menu.addView(menuItem("STOP", v -> { hideMenu(); stopEverything(); }));

            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    dp(128), WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT
            );
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = Math.max(dp(8), bubbleParams.x - dp(80));
            p.y = bubbleParams.y + dp(64);
            try { windowManager.addView(menu, p); } catch (Exception e) { menu = null; }
        });
    }

    private TextView menuItem(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setOnClickListener(click);
        return t;
    }

    private void hideMenu() {
        if (menu != null && windowManager != null) {
            try { windowManager.removeView(menu); } catch (Exception ignored) {}
            menu = null;
        }
    }

    private void renderSession(ScanSession session) {
        if (session != activeSession || session.cancelled) return;
        mainHandler.post(() -> {
            if (session != activeSession || session.cancelled || shuttingDown) return;
            String text = session.render();
            if (resultCard == null) createResultCard();
            if (resultCard != null) {
                resultCard.setText(text);
                resultCard.setVisibility(View.VISIBLE);
            }
        });
    }

    private void createResultCard() {
        if (windowManager == null || resultCard != null) return;
        resultCard = new TextView(this);
        resultCard.setTextColor(Color.WHITE);
        resultCard.setTextSize(13);
        resultCard.setPadding(dp(14), dp(11), dp(14), dp(11));
        resultCard.setBackground(roundedBackground(0xF01C1E23, dp(18)));
        resultCard.setMaxWidth(dp(350));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT
        );
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = dp(70);
        try { windowManager.addView(resultCard, p); } catch (Exception e) { resultCard = null; }
    }

    private void updateLine(ScanSession session, String key, String value) {
        if (session == null || session != activeSession || session.cancelled) return;
        synchronized (session.lines) {
            session.lines.put(key, value);
        }
        renderSession(session);
    }

    private String visualScore(AiApiClient.ScoreResult r) {
        String s = classification(r.aiScore, "AI visual", "real visual");
        if (!r.generator.isEmpty() && r.aiScore >= 0.5) s += " • " + r.generator;
        return s;
    }

    private String classification(double ai, String aiLabel, String realLabel) {
        if (ai >= 0.70) return String.format(Locale.US, "%s %.0f%%", aiLabel, ai * 100.0);
        if (ai <= 0.30) return String.format(Locale.US, "%s %.0f%%", realLabel, (1.0 - ai) * 100.0);
        return String.format(Locale.US, "uncertain • AI %.0f%%", ai * 100.0);
    }

    private String classificationShort(double ai) {
        if (ai >= 0.70) return String.format(Locale.US, "AI %.0f%%", ai * 100.0);
        if (ai <= 0.30) return String.format(Locale.US, "REAL %.0f%%", (1.0 - ai) * 100.0);
        return String.format(Locale.US, "? AI %.0f%%", ai * 100.0);
    }

    private GradientDrawable circleBackground(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(2), 0x88FFFFFF);
        return g;
    }

    private GradientDrawable roundedBackground(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke(dp(1), 0x44FFFFFF);
        return g;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AI Screen Check",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Required while the floating AI checker has screen-capture access");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent scanIntent = new Intent(this, CaptureService.class).setAction(ACTION_SMART_SCAN);
        PendingIntent scanPending = PendingIntent.getService(
                this, 100, scanIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 101, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return b.setSmallIcon(R.drawable.ic_ai)
                .setContentTitle("AI Screen Check")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_ai), "SMART SCAN", scanPending).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_ai), "STOP", stopPending).build())
                .build();
    }

    private synchronized void stopEverything() {
        if (shuttingDown) return;
        shuttingDown = true;
        ScanSession s = activeSession;
        if (s != null) s.cancelled = true;

        mainHandler.post(() -> {
            hideMenu();
            if (resultCard != null && windowManager != null) {
                try { windowManager.removeView(resultCard); } catch (Exception ignored) {}
                resultCard = null;
            }
            if (bubble != null && windowManager != null) {
                try { windowManager.removeView(bubble); } catch (Exception ignored) {}
                bubble = null;
            }
        });

        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        if (textRecognizer != null) {
            try { textRecognizer.close(); } catch (Exception ignored) {}
            textRecognizer = null;
        }
        networkExecutor.shutdownNow();
        audioExecutor.shutdownNow();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (!shuttingDown) stopEverything();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Intent parcelableIntent(Intent source, String key) {
        if (Build.VERSION.SDK_INT >= 33) return source.getParcelableExtra(key, Intent.class);
        return source.getParcelableExtra(key);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String mergeText(String a, String b) {
        String aa = a == null ? "" : a.trim();
        String bb = b == null ? "" : b.trim();
        if (aa.isEmpty()) return bb;
        if (bb.isEmpty()) return aa;
        if (aa.contains(bb)) return aa;
        if (bb.contains(aa)) return bb;
        return aa + "\n" + bb;
    }

    private static String shortMessage(Exception e) {
        String m = e == null ? "unknown" : e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e == null ? "unknown" : e.getClass().getSimpleName();
        m = m.replace('\n', ' ').trim();
        return m.length() <= 54 ? m : m.substring(0, 53) + "…";
    }

    private static int maxPcm16(byte[] data, int length) {
        int max = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int lo = data[i] & 0xff;
            int hi = data[i + 1];
            int sample = (short) ((hi << 8) | lo);
            max = Math.max(max, Math.abs(sample));
        }
        return max;
    }

    private static byte[] makeWav(byte[] pcm, int sampleRate, int channels, int bits) throws Exception {
        int byteRate = sampleRate * channels * bits / 8;
        int dataLen = pcm.length;
        int riffLen = 36 + dataLen;
        ByteArrayOutputStream out = new ByteArrayOutputStream(dataLen + 44);
        writeAscii(out, "RIFF");
        writeLe32(out, riffLen);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLe32(out, 16);
        writeLe16(out, 1);
        writeLe16(out, channels);
        writeLe32(out, sampleRate);
        writeLe32(out, byteRate);
        writeLe16(out, channels * bits / 8);
        writeLe16(out, bits);
        writeAscii(out, "data");
        writeLe32(out, dataLen);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) throws Exception {
        out.write(s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private enum FrameKind { IMAGE, TEXT, SMART, VIDEO }

    private static final class FrameTask {
        final FrameKind kind;
        final ScanSession session;
        final int index;
        final int total;

        FrameTask(FrameKind kind, ScanSession session, int index, int total) {
            this.kind = kind;
            this.session = session;
            this.index = index;
            this.total = total;
        }

        String lineKey() {
            switch (kind) {
                case IMAGE: return "Image";
                case TEXT: return "Text";
                case VIDEO: return "Video";
                default: return "Visual";
            }
        }
    }

    private static final class ScanSession {
        final String title;
        final Map<String, String> lines = new LinkedHashMap<>();
        volatile boolean cancelled = false;
        private int videoDone = 0;
        private int videoErrors = 0;
        private double videoSum = 0.0;
        private double videoMax = 0.0;

        ScanSession(String title) {
            this.title = title;
        }

        synchronized void addVideoScore(double score, int total) {
            videoDone++;
            videoSum += score;
            videoMax = Math.max(videoMax, score);
            updateVideoLine(total);
        }

        synchronized void addVideoError(int total) {
            videoDone++;
            videoErrors++;
            updateVideoLine(total);
        }

        private void updateVideoLine(int total) {
            int success = videoDone - videoErrors;
            String value;
            if (videoDone < total) {
                value = "frames " + videoDone + "/" + total + "…";
            } else if (success <= 0) {
                value = "frame analysis failed";
            } else {
                double avg = videoSum / success;
                String result;
                if (avg >= 0.70 || videoMax >= 0.85) {
                    result = String.format(Locale.US, "AI-like %.0f%% avg • max %.0f%%", avg * 100.0, videoMax * 100.0);
                } else if (avg <= 0.30 && videoMax <= 0.45) {
                    result = String.format(Locale.US, "real-like %.0f%% avg", (1.0 - avg) * 100.0);
                } else {
                    result = String.format(Locale.US, "uncertain • AI %.0f%% avg", avg * 100.0);
                }
                value = result + " • frame scan";
            }
            synchronized (lines) {
                lines.put("Video", value);
            }
        }

        String render() {
            StringBuilder sb = new StringBuilder();
            sb.append(title);
            synchronized (lines) {
                for (Map.Entry<String, String> e : lines.entrySet()) {
                    sb.append('\n').append(e.getKey()).append(": ").append(e.getValue());
                }
            }
            return sb.toString();
        }
    }
}
