package com.panos.aiscreencheck;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class CopyleaksClient {
    private final String email;
    private final String apiKey;
    private volatile String token = "";
    private volatile long tokenAt = 0L;

    public CopyleaksClient(String email, String apiKey) {
        this.email = email == null ? "" : email.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isConfigured() {
        return !email.isEmpty() && !apiKey.isEmpty();
    }

    public TextResult detectText(String text) throws Exception {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.length() < 255) {
            return TextResult.tooShort(cleaned.length());
        }
        if (cleaned.length() > 100000) cleaned = cleaned.substring(0, 100000);
        String auth = getToken();
        String scanId = "mob-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        URL url = new URL("https://api.copyleaks.com/v2/writer-detector/" + scanId + "/check");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(45000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", "Bearer " + auth);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

        JSONObject body = new JSONObject();
        body.put("text", cleaned);
        body.put("sandbox", false);
        body.put("explain", false);
        body.put("sensitivity", 2);
        writeJson(conn, body);

        JSONObject root = readJsonResponse(conn);
        double ai = Double.NaN;
        double human = Double.NaN;
        JSONObject summary = root.optJSONObject("summary");
        if (summary != null) {
            ai = summary.optDouble("ai", Double.NaN);
            human = summary.optDouble("human", Double.NaN);
        }
        if (!Double.isNaN(ai) && !Double.isNaN(human) && ai + human > 1.0001) {
            double total = ai + human;
            ai /= total;
            human /= total;
        }
        if (Double.isNaN(ai)) ai = deriveFromSections(root);
        if (Double.isNaN(ai)) throw new IllegalStateException("No AI text score returned");
        return new TextResult(Math.max(0.0, Math.min(1.0, ai)), cleaned.length(), false);
    }

    private synchronized String getToken() throws Exception {
        if (!token.isEmpty() && System.currentTimeMillis() - tokenAt < 36L * 60L * 60L * 1000L) return token;
        URL url = new URL("https://id.copyleaks.com/v3/account/login/api");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("key", apiKey);
        writeJson(conn, body);
        JSONObject root = readJsonResponse(conn);
        String value = root.optString("access_token", "");
        if (value.isEmpty()) throw new IllegalStateException("Copyleaks login failed");
        token = value;
        tokenAt = System.currentTimeMillis();
        return token;
    }

    private static void writeJson(HttpURLConnection conn, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static JSONObject readJsonResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();
        if (sb.length() == 0) throw new IllegalStateException("Empty Copyleaks response (HTTP " + code + ")");
        JSONObject root = new JSONObject(sb.toString());
        if (code < 200 || code >= 300) {
            String msg = root.optString("message", root.optString("error", "Copyleaks HTTP " + code));
            throw new IllegalStateException(msg);
        }
        return root;
    }

    private static double deriveFromSections(JSONObject root) {
        JSONArray arr = root.optJSONArray("results");
        if (arr == null) arr = root.optJSONArray("result");
        if (arr == null || arr.length() == 0) return Double.NaN;
        int aiCount = 0;
        int total = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            JSONArray nested = item.optJSONArray("result");
            if (nested != null) {
                for (int j = 0; j < nested.length(); j++) {
                    JSONObject s = nested.optJSONObject(j);
                    if (s == null) continue;
                    int c = s.optInt("classification", 0);
                    if (c == 2) aiCount++;
                    if (c == 1 || c == 2) total++;
                }
            } else {
                int c = item.optInt("classification", 0);
                if (c == 2) aiCount++;
                if (c == 1 || c == 2) total++;
            }
        }
        return total == 0 ? Double.NaN : aiCount / (double) total;
    }

    public static final class TextResult {
        public final double aiScore;
        public final int chars;
        public final boolean tooShort;

        public TextResult(double aiScore, int chars, boolean tooShort) {
            this.aiScore = aiScore;
            this.chars = chars;
            this.tooShort = tooShort;
        }

        public static TextResult tooShort(int chars) {
            return new TextResult(Double.NaN, chars, true);
        }
    }
}
