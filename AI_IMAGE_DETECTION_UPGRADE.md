# AI image detection upgrade

Updated `AI-Screen-Check/app/src/main/java/com/panos/aiscreencheck/CaptureService.java`.

## Changes
- Screen capture width raised from 900px to max 1440px.
- JPEG quality raised from 84 to 95 for AI-image analysis.
- Adaptive multi-sample detection:
  - 1 API call for very clear results (AI >= 88% or AI <= 12%).
  - Otherwise checks an 82% centered crop.
  - Adds a 64% crop only when scores disagree strongly or remain near 50/50.
- Final ambiguous multi-sample score uses the median instead of blindly trusting one screenshot.
- If crops disagree by >= 40 percentage points, the app reports `uncertain` instead of a false confident AI/real verdict.
- Visual AI/real verdict thresholds are now 75% / 25% for image checks.
- Smart Scan uses a separate bitmap copy so OCR and image analysis can safely run concurrently.

## Why
The old pipeline analyzed one UI-heavy full-screen screenshot at reduced resolution and JPEG quality 84. A toolbar, captions, app chrome, or screenshot artifacts could therefore distort a single Sightengine score. The new adaptive ensemble is more robust while avoiding 3 API calls for every image.

## API usage
Clear image: 1 request.
Ambiguous image: 2 requests.
Conflicting/near-50% image: 3 requests.
