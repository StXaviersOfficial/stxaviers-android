# XavierDrive — Android App

The official **XavierDrive** app for **St. Xavier's Jr./Sr. School, Goshala Road,
Ramna, Muzaffarpur (Bihar) 842002** — native Android, pure Java, zero Gradle.

**Repo:** public · **Current release:** v1.2.2 (versionCode 25) ·
**Download:** https://stxaviers.pages.dev/apk/xavierdrive1.2.2.apk

> **This repository contains ONLY the XavierDrive app** (source, resources,
> fonts, build script). The owner's separate device-rig system (XD Assist
> accessibility app + Termux bridge) lives in its own repo:
> **`StXaviersOfficial/xd-assist`** — it is *not* part of this app and must
> not be swept, refactored, or mixed up with it. Release binaries are never
> committed here; APKs ship via the update channel (Cloudflare Pages +
> worker `APP_LATEST`).

## What's inside

Pure Java + platform views (no Gradle, no AndroidX UI framework) — built with
`aapt2 + javac + d8 + zipalign + apksigner` (see `build-apk.sh`; it merges the
Credential Manager AAR stack at build time).

**~52 Java classes, 20 activities** — the native portal:

- **SplashActivity** — cinematic splash + in-app updater with true progress,
  download speed/ETA, auto-install hand-off, and (since v1.2.2) an
  **APK SHA-256 integrity gate**: the downloaded update is verified against
  the hash pinned in the worker's `APP_LATEST` before it can ever install.
- **LoginActivity / NativeGoogleSignIn** — Google Credential Manager
  bottom-sheet picker → legacy GoogleSignIn fallback → Play Services rescue
  → WebView sign-in. ID token exchanged for the school session at the worker.
- **HomeActivity** — dashboard with role-aware cards; since v1.2.2 it
  **aggregates announcements** (ANNOUNCEMENTS folder + dashboard notices
  deduplicated into one feed).
- **FilesActivity** — the Google Drive folder tree (FILES per class) with
  in-app preview/download.
- **NoticesActivity** — the Notice Hub.
- **TimetableActivity / AttendanceActivity / LogbookActivity** — class
  timetable, attendance tiles, teacher logbook.
- **LiveActivity + AdminLiveActivity** — live chat channel (ChatSync
  long-poll), with an admin console view.
- **AiActivity + AiSettingsActivity** — the in-app AI assistant, voice input
  (VoiceInput), TTS playback (TtsPlayer).
- **AdminRolesActivity / AdminFilesActivity** — school admin tools
  (role roster, Drive file management).
- **BugReportActivity** — device-specs + latestlog.txt bug reports; since
  v1.2.1 crash-proofed against layout/view-type drift (the empty-hub bug).
- **ProfileActivity / SchoolActivity / SettingsActivity / LegalActivity** —
  profile sync, school info, settings, ToS/privacy viewer.

Background views (AuroraView, StarfieldView, FabricSpaceView,
AnimatedGradientView, ShimmerTextView, WaveformView, WordmarkView) render the
animated UI; since v1.2.3 they **pause their animators when detached** and
respect the system animator-duration scale.

## Architecture

```
app (this repo)  ──HTTPS──▶  Cloudflare Worker (repo: xavier-drive)
                                │  auth · roles · sessions · bug reports
                                │  APP_LATEST update manifest (sha256-pinned)
                                ▼
                          Google Drive (school storage: FILES, LOGBOOK,
                          ANNOUNCEMENTS, TIMETABLE folders)
```

The worker also proxies the owner's device-rig endpoints (`/rig/*`,
`/internal/device-tunnel`) — rig code itself lives in `xd-assist`.

## Build

```bash
bash build-apk.sh   # needs android-sdk (build-tools 34, platform 34) + JDK17
```

Build downloads nothing at runtime; the Credential Manager AARs are merged
from a local directory (see the script header). APKs are signed with the
`stxaviers-upload` release key — its SHA-1 is registered on the Android
OAuth client, so updates MUST keep using that key.

Every release must bump the update channel too (worker `APP_LATEST` +
Pages APK) — that's how the app's updater learns about new versions.

## Improvement backlog (for AI-assisted sweeps)

Curated so a code-review agent knows what is **already done** and what is
**pre-approved** to change — please don't redo the done items:

- [x] *v1.2.1:* bug-report hub ClassCastException root-caused and fixed
- [x] *v1.2.2:* APK sha256 integrity gate on the update path
- [x] *v1.2.3:* custom views pause animators on detach (battery/jank)
- [x] *v1.2.3:* typed `ViewUtils.find(view, id, Class)` helper + audit of
      every `findViewById` cast against the XML layouts (the whole
      ClassCastException bug class, not just one instance)
- [x] *S26:* APKs/idsig binaries purged from Git tree; `.gitignore` guards
      added; XD Assist + Termux bridge moved to `xd-assist` repo
- [ ] **SQLite offline cache** for Notices / Timetable / Attendance
      (`android.database.sqlite`, zero external deps — fits the no-Gradle
      build) so students see content instantly instead of waiting on the
      network. **Highest-value remaining item.**
- [ ] **Worker auth hardening**: replace the long-lived session cookie with
      short-lived, role-scoped HMAC-signed tokens (issued at login by
      `GoogleAuth`/`AuthActivity`, verified by the worker) so a decompiled
      APK can't spoof admin requests.
- [ ] **Package modularization**: split the flat `com.stxaviers.app` package
      into `ui.*`, `data.*`, `bridge.*` subpackages (mechanical rename —
      update `build-apk.sh` source list, no other build impact).
- [ ] **HTTP layer modernization** (only if the zero-dependency constraint is
      relaxed): OkHttp/Retrofit + ETag caching, or add retry/backoff to
      `ApiClient` as the dependency-free version.
- [ ] **R8 shrinking** in `build-apk.sh` (needs an R8 jar in the pipeline).
