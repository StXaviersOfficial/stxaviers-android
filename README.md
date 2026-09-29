# XavierDrive — Android App

The official **XavierDrive** app for **St. Xavier's Jr./Sr. School, Goshala Road,
Ramna, Muzaffarpur (Bihar) 842002** — native Android, zero Gradle.

**Repo:** public · **Current release:** v2.0.0 (versionCode 14) ·
**Download:** https://stxaviers.pages.dev/apk/xavierdrive2.0.0.apk

## What's inside

Pure Java + platform views (no Gradle, no AndroidX UI framework) — built with
`aapt2 + javac + d8 + zipalign + apksigner` (see `build-apk.sh`).

- **SplashActivity** — cinematic splash + the full in-app updater: true
  progress bar with real APK size, live download speed + ETA, auto install
  dialog, install-unknown-apps permission hand-off.
- **LoginActivity** — the animated login page (aurora, starfield, 3D-tilt
  glass card, magnetic Google button), auto light/dark.
- **NativeGoogleSignIn** — three-step chain: Google Credential Manager
  (instant bottom-sheet picker with every device account) -> the classic
  legacy GoogleSignIn account chooser (fires automatically wherever
  Credential Manager is unsupported — the fix for
  GetCredentialProviderConfigurationException) -> Play Services update
  rescue -> WebView sign-in. ID token exchanged for the school session at
  `POST /api/auth/mobile` (worker).
- **FabricSpaceView** — the animated space-fabric background. Splash
  (update checker) runs a real GPU fluid shader on Android 13+ (AGSL
  domain-warped fBm — continuously mixing colours) with an upgraded
  SCREEN-blended canvas nebula on older devices; the old software-layer
  bug that froze the animation on-device is gone.
- **LegalActivity** — in-app Terms of Service / Privacy Policy viewer
  linked from the login note.
- **Typography** — every display font verified to draw GENUINE lowercase
  (Bungee/Bebas removed: their lowercase is just small caps). Login:
  Shrikhand title, Poppins school line (static), Rubik Glitch
  "Welcome back" with full glitch animation. Splash: Monoton title,
  Rubik Puddles status, VT323 version.
- **MainActivity** — the full portal in a hardened WebView with
  blank-page self-healing watchdog. Since v2.0.0 the app shell owns
  sign-in: any attempt by the embedded site to navigate into the web
  Google form (worker `/login`, `accounts.google.com`) is intercepted
  and routed back to the app's own login screen, and a boot-time
  `/me` probe sends dead sessions straight to sign-in — the portal can
  never host a web login or dead-end into "please wait".
- **UpdateCheck** — reads the installed version from the PackageManager
  (never a stale constant) and compares against `/api/app/version`.

## v2.0.0 — the login-loop fix

Sign-in worked (picker, "Welcome" toast) but the app then bounced to the
"please wait" screen and into Google's web email/password page, every time —
even automatically on app open. The native ID-token sign-in creates a session
with no Google access token (an ID token can't mint one), and the portal's
`/token` check treated that as "not signed in". Fixed in two layers: the
worker now issues an app-scoped session token for native sessions, and the
app intercepts any portal attempt to start a web sign-in.

## Sign-in credentials

Google Cloud project `stxaviersapp` — see `credentials.txt` (web client,
Android client SHA-1, Digital Asset Links). APKs are signed with the
`stxaviers-upload` release key; its SHA-1 is registered on the Android
OAuth client, so updates MUST keep using that key.

## Build

```bash
bash build-apk.sh   # needs android-sdk (build-tools 34, platform 34) + JDK17
```

The build downloads nothing at runtime — it merges the Credential Manager
AAR stack (androidx.credentials, googleid, play-services) from
`/tmp/my-project/aars`; see the script header for the manual AAR pipeline.
