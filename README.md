# NOVA Launcher

Android launcher (Kotlin, no external dependencies, minSdk 26).

- Wallpaper home with a glass clock, live Battery and Storage widgets
- Every installed app rendered as an iOS-style **black icon** (squircle, rim light, top sheen)
- Floating glass dock (Phone, Messages, Camera, Browser — your default apps)
- Spring press feedback and scale-up app launch

## Get the APK

**Option A — Android Studio:** open this folder, let Gradle sync, then Build > Build APK(s).
The file is `app/build/outputs/apk/debug/app-debug.apk`.

**Option B — GitHub Actions (no install):** push this folder to a GitHub repo.
Open the Actions tab, run "Build NOVA APK", and download the `nova-launcher-apk` artifact.

Install the APK, press Home, and choose NOVA as the default home app.
