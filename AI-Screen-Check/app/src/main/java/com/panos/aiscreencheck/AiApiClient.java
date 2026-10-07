package com.panos.aiscreencheck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

public class AiApiClient {
    private final String apiUser;
    private final String apiSecret;

    public AiApiClient(String apiUser, String apiSecret) {
        this.apiUser = apiUser == null ? "" : apiUser.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
    }

    public boolean isConfigured() {
        return !apiUser.isEmpty() && !apiSecret.isEmpty();
    }

    public ScoreResult analyzeImage(byte[] bytes) throws Exception {
        return analyzeImage(bytes, "screen.jpg", "image/jpeg");
    }

    public ScoreResult analyzeImage(byte[] bytes, String filename, String mime) throws Exception {
        JSONObject root = postBytes(
                "https://api.sightengine.com/1.0/check.json",
                "media", safeFilename(filename, "screen.jpg"), safeMime(mime, "image/jpeg"), bytes,
                new String[][]{{"models", "genai"}, {"api_user", apiUser}, {"api_secret", apiSecret}}
        );
        ensureSuccess(root);
        JSONObject type = root.optJSONObject("type");
        if (type == null || !type.has("ai_generated")) {
            throw new IllegalStateException("No AI image score returned");
        }
        double score = type.optDouble("ai_generated", 0.5);
        return new ScoreResult(score, topGenerator(type.optJSONObject("ai_generators")));
    }

    public AudioResult analyzeAudio(byte[] wav) throws Exception {
        try {
            JSONObject root = postBytes(
                    "https://api.sightengine.com/1.0/audio/check.json",
                    "audio", "sample.wav", "audio/wav", wav,
                    new String[][]{{"models", "ai_music,ai_speech"}, {"api_user", apiUser}, {"api_secret", apiSecret}}
            );
            ensureSuccess(root);
            JSONObject type = root.optJSONObject("type");
            return new AudioResult(
                    type == null ? Double.NaN : type.optDouble("ai_music", Double.NaN),
                    type == null ? Double.NaN : type.optDouble("ai_speech", Double.NaN),
                    false
            );
        } catch (Exception gatedSpeech) {
            JSONObject root = postBytes(
                    "https://api.sightengine.com/1.0/audio/check.json",
                    "audio", "sample.wav", "audio/wav", wav,
                    new String[][]{{"models", "ai_music"}, {"api_user", apiUser}, {"api_secret", apiSecret}}
            );
            ensureSuccess(root);
            JSONObject type = root.optJSONObject("type");
            return new AudioResult(
                    type == null ? Double.NaN : type.optDouble("ai_music", Double.NaN),
                    Double.NaN,
                    true
            );
        }
    }

    public AudioResult analyzeAudioFile(File audioFile) throws Exception {
        JSONObject root;
        try {
            root = postFile(
                    "https://api.sightengine.com/1.0/audio/check.json",
                    "audio", audioFile, guessAudioMime(audioFile.getName()),
                    new String[][]{{"models", "ai_music,ai_speech"}, {"api_user", apiUser}, {"api_secret", apiSecret}}
            );
            ensureSuccess(root);
            JSONObject type = root.optJSONObject("type");
            return new AudioResult(
                    type == null ? Double.NaN : type.optDouble("ai_music", Double.NaN),
                    type == null ? Double.NaN : type.optDouble("ai_speech", Double.NaN),
                    false
            );
        } catch (Exception gatedSpeech) {
            root = postFile(
                    "https://api.sightengine.com/1.0/audio/check.json",
                    "audio", audioFile, guessAudioMime(audioFile.getName()),
                    new String[][]{{"models", "ai_music"}, {"api_user", apiUser}, {"api_secret", apiSecret}}
            );
            ensureSuccess(root);
            JSONObject type = root.optJSONObject("type");
            return new AudioResult(
                    type == null ? Double.NaN : type.optDouble("ai_music", Double.NaN),
                    Double.NaN,
                    true
            );
        }
    }

    public VideoResult analyzeVideo(File videoFile) throws Exception {
        return analyzeVideo(videoFile, guessVideoMime(videoFile.getName()));
    }

    public VideoResult analyzeVideo(File videoFile, String mime) throws Exception {
        JSONObject root = postFile(
                "https://api.sightengine.com/1.0/video/check-sync.json",
                "media", videoFile, safeMime(mime, guessVideoMime(videoFile.getName())),
                new String[][]{{"models", "genai"}, {"interval", "1"}, {"api_user", apiUser}, {"api_secret", apiSecret}}
        );
        ensureSuccess(root);
        JSONObject data = root.optJSONObject("data");
        JSONArray frames = data == null ? null : data.optJSONArray("frames");
        if (frames == null || frames.length() == 0) throw new IllegalStateException("No video frames returned");
        double sum = 0.0;
        double max = 0.0;
        int count = 0;
        for (int i = 0; i < frames.length(); i++) {
            JSONObject frame = frames.optJSONObject(i);
            JSONObject type = frame == null ? null : frame.optJSONObject("type");
            if (type == null || !type.has("ai_generated")) continue;
            double s = type.optDouble("ai_generated", 0.5);
            sum += s;
            max = Math.max(max, s);
            count++;
        }
        if (count == 0) throw new IllegalStateException("No AI video score returned");
        return new VideoResult(sum / count, max, count);
    }

    private JSONObject postBytes(String endpoint, String fileField, String filename, String mime, byte[] bytes, String[][] fields) throws Exception {
        return postMultipart(endpoint, fileField, filename, mime, new ByteArrayInputStream(bytes), bytes.length, fields);
    }

    private JSONObject postFile(String endpoint, String fileField, File file, String mime, String[][] fields) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            return postMultipart(endpoint, fileField, file.getName(), mime, in, file.length(), fields);
        }
    }

    private JSONObject postMultipart(String endpoint, String fileField, String filename, String mime, InputStream input, long length, String[][] fields) throws Exception {
        String boundary = "----AICheck" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(32 * 1024);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
            for (String[] field : fields) writeField(out, boundary, field[0], field[1]);
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + filename.replace("\"", "") + "\"\r\n");
            out.writeBytes("Content-Type: " + mime + "\r\n\r\n");
            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
        }

        int code = conn.getResponseCode();
        InputStream responseStream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(responseStream);
        conn.disconnect();
        if (body == null || body.trim().isEmpty()) throw new IllegalStateException("Empty API response (HTTP " + code + ")");
        JSONObject root = new JSONObject(body);
        if (code < 200 || code >= 300) throw new IllegalStateException(errorMessage(root, "HTTP " + code));
        return root;
    }

    private static void writeField(DataOutputStream out, String boundary, String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void ensureSuccess(JSONObject root) {
        if ("success".equalsIgnoreCase(root.optString("status"))) return;
        throw new IllegalStateException(errorMessage(root, "API failure"));
    }

    private static String errorMessage(JSONObject root, String fallback) {
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            String m = error.optString("message", "");
            if (!m.isEmpty()) return m;
        }
        return fallback;
    }

    private static String topGenerator(JSONObject generators) {
        if (generators == null) return "";
        String best = "";
        double bestScore = 0.0;
        Iterator<String> it = generators.keys();
        while (it.hasNext()) {
            String key = it.next();
            double score = generators.optDouble(key, 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        if (bestScore < 0.35) return "";
        return best.replace('_', ' ').toUpperCase(Locale.US);
    }


    private static String guessVideoMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        return "video/mp4";
    }

    private static String safeFilename(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? fallback : v.replace("\"", "");
    }

    private static String safeMime(String value, String fallback) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? fallback : v;
    }

    private static String guessAudioMime(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".webm")) return "audio/webm";
        return "audio/mpeg";
    }

    public static final class ScoreResult {
        public final double aiScore;
        public final String generator;
        public ScoreResult(double aiScore, String generator) {
            this.aiScore = aiScore;
            this.generator = generator == null ? "" : generator;
        }
    }

    public static final class AudioResult {
        public final double musicAi;
        public final double speechAi;
        public final boolean speechUnavailable;
        public AudioResult(double musicAi, double speechAi, boolean speechUnavailable) {
            this.musicAi = musicAi;
            this.speechAi = speechAi;
            this.speechUnavailable = speechUnavailable;
        }
    }

    public static final class VideoResult {
        public final double averageAi;
        public final double maxAi;
        public final int frames;
        public VideoResult(double averageAi, double maxAi, int frames) {
            this.averageAi = averageAi;
            this.maxAi = maxAi;
            this.frames = frames;
        }
    }
}
