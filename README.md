# AI Screen Check 2.0 — Android

A floating, on-demand AI-content checker for Android.

## Fast workflow

1. Start the app and grant overlay + screen-capture permission.
2. Optionally enable the Accessibility service for better visible-text extraction.
3. Open TikTok, Instagram, a browser, gallery, etc.
4. **Tap the floating blue `AI` circle** for a Smart Scan.
5. **Long-press the circle (~0.7 s)** for individual modes: IMAGE, TEXT, AUDIO, VIDEO, STOP.
6. Drag the circle to reposition it.
7. You can also use **SMART SCAN** directly from the persistent Android notification while the service is active.

## What Smart Scan checks

- **Visual / image:** current visible frame -> Sightengine `genai` image detector.
- **Text:** visible UI text via Android Accessibility; OCR fallback via on-device Google ML Kit; AI-writing classification via Copyleaks.
- **Audio / music:** captures ~8 seconds of capturable device playback -> Sightengine `ai_music`; also requests `ai_speech` when the Sightengine account has access.

Results appear as a floating card above the current app.

## Dedicated modes

### IMAGE
Checks one current screen frame for generative-AI visual content.

### TEXT
Checks the current visible text. Copyleaks currently requires at least **255 characters** for its synchronous AI-text endpoint. Greek is supported by Copyleaks. Accessibility text is preferred because the bundled ML Kit OCR fallback is strongest for Latin-script text.

### AUDIO
Captures ~8 seconds of playback audio and checks it for AI music. AI speech detection is attempted too, but Sightengine currently gates `ai_speech` access to enterprise/partner accounts. If unavailable, the app falls back to music detection only.

### VIDEO
The live overlay cannot obtain the original TikTok/Instagram video file. This mode samples five screen frames and aggregates visual-AI scores. Treat this as a **live video-frame estimate**, not the same thing as Sightengine's dedicated video model.

For true AI-video detection, use **Analyze file** in the main app and select a local video of up to 60 seconds. That path uploads the actual video file to Sightengine's synchronous video `genai` endpoint.

## File scan

The main screen also supports local files:

- Images -> AI image detector
- Videos up to 60 seconds -> dedicated AI video detector
- Audio -> AI music + AI speech when enabled
- TXT -> Copyleaks AI text detector

## Required API accounts

### Sightengine
Used for image, video, music, and optional speech detection.

You need:
- `api_user`
- `api_secret`

### Copyleaks
Used for AI-generated text classification.

You need:
- account email
- API key

Copyleaks is optional if you do not need text AI detection.

## Android permissions

- `SYSTEM_ALERT_WINDOW` — floating AI button/result card
- `MediaProjection` consent — screen capture session
- `RECORD_AUDIO` — Android playback capture API
- Accessibility service — optional but strongly recommended for visible screen text
- Internet — detector APIs

## Android audio limitation

Android playback capture can only record audio from apps/players that permit capture. Some apps deliberately block it. In that case the app reports that no capturable audio was available.

## Accuracy limitation

Every result is probabilistic. These tools can produce false positives and false negatives, particularly with:

- heavily edited or recompressed media
- screenshots containing lots of UI
- short text
- paraphrased/mixed AI-human text
- very short or noisy audio
- video checked only through sampled screen frames

Use the score as evidence, not proof of authorship or authenticity.

## Privacy

The app does not intentionally save Smart Scan screenshots, OCR text, or captured playback audio permanently. Those samples are held transiently and sent to the configured third-party detection APIs only when a scan is triggered.

Do **not** trigger a scan while passwords, one-time codes, banking information, confidential chats, medical records, or other sensitive content are visible/audible.

## Build requirements

- A current Android Studio version that supports Android Gradle Plugin 9.4.0
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- JDK 17
- Android SDK Platform 36+
- SDK Build Tools 36.0.0+

Open the project in Android Studio, sync Gradle, then use **Build > Build APK(s)**.
