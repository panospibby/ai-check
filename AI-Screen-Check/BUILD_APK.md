# Build APK

This project includes `.github/workflows/build-apk.yml`.

## GitHub Actions
1. Push the project to a GitHub repository.
2. Open **Actions > Build Android APK**.
3. Press **Run workflow**.
4. After the run completes, download the artifact **AI-Screen-Check-debug-apk**.
5. Inside it is `app-debug.apk`.

The workflow installs JDK 17, Android SDK 36 / Build Tools 36.0.0 and Gradle 9.6.0, then runs `:app:assembleDebug`.
