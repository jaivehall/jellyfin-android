# Changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.5] - 2026-11-20

> Includes all changes accumulated since the `1.0.1` release.

### Added

- **"Underwater reflection" brand visuals**: Mint `#00E0A4` + Ink `#0C1417` palette with a fin-shaped play mark. The in-app logo, launcher icon (including all dpi PNGs), splash, WebView icon and banner all share the same treatment — a solid upper half, a fading reflection below, and a waterline highlight. VectorDrawable simulates the fade with slices of decreasing alpha; the WebView SVG uses a real `linearGradient`.
- **Localized app name on the splash screen**: white wordmarks (`Finplayer` / `飞影视界` / `飛影視界`) are drawn inside the system SplashScreen icon and switched via resource qualifiers. The logo-to-text ratio was settled after comparing several layout options.
- **"About" entry in the user menu**: an "About" item was added above "Sign out" in the MUI user menu inside the WebView. Tapping it opens a dark dialog showing the reflection logo, the localized app name and the version number, with Simplified Chinese / Traditional Chinese / English copy and automatic re-insertion after React re-renders.
- **Branded side drawer header**: the drawer header now always shows the app name plus the app version instead of `Jellyfin` or the server name and server version. This is done with display-layer isolation — the original `ListItemText` is hidden and a fixed brand block is inserted — so the asynchronous `useSystemInfo()` update after sign-in no longer causes a flicker.
- **Build-time injection of the default server address**: the address is no longer hard-coded. It is injected into `BuildConfig` at build time from `local.properties`, an environment variable, or a Gradle property. A `local.properties.example` template is provided, and when unset the app falls back to the native connect screen.
- **Unified `/Items` sorting**: both the native OkHttp interceptor `ItemsSortInterceptor` and the WebView script `itemsSortOverride.js` rewrite `sortBy` (DateCreated / ProductionYear / PremiereDate / SortName) and `sortOrder` (Descending), while respecting any sort the user picks manually in the UI and keeping each library isolated.
- **Localized app name bridge**: `NativeInterface.getDeviceInformation()` now exposes `localizedAppName`, which the WebView scripts use to replace brand copy globally instead of hard-coding English.

### Fixed

- Brand assets not synced in debug builds: four stale override files under `app/src/debug` (launcher foreground, app_logo, splash fill color, colors) were updated to match the new brand assets in `main`.
- Launcher fin too large and clipped by the adaptive-icon safe zone: the foreground scale was reduced step by step and centered to leave a margin on all sides.
- `Jellyfin` copy and the old "three F" logo left on the login title and menu header: they now read the localized app name from the native bridge and use the new Mint icon.
- DLNA progress-bar seeking reworked into "restart playback" style seeks (`startTimeTicks` on the server side), fixing: jumping back to the original position after a seek (reused `playSessionId`), `static=true` direct-play profiles ignoring the start position, concurrent seeks overwriting each other's sessions, false successes degrading from the direct profile, and telemetry misjudgment on transcoded profiles.
- No audio on old TVs with AAC tracks in the remux-ts profile: whether to force-transcode to AC3 is now decided by the source codec, so video is still stream-copied to keep quality and the user's chosen audio track is preserved.
- HarmonyOS devices reporting "There was a problem parsing the package" on install: the APK signature, SDK levels, manifest, dex and ZIP structure were all verified healthy; the issue was traced to the distribution/download step and device-side restrictions rather than a code defect.

### Changed

- Version `1.0.1` → `1.0.5`; Android Gradle Plugin `9.4.1` → `9.5.1`.
- Brand copy replaced across all language `strings.xml` files and `strings_donottranslate.xml`; `translatable=false` removed from `app_name` so it can be localized.
- `.gitignore` gained rules for local work data and build outputs, so files containing private information are never committed to the public repository.

## [1.0.1] - 2026-11-18

### Added

- **DLNA casting**: automatically discovers MediaRenderer TVs on the local network and merges them with Google Cast into a single casting entry point with parallel device search.
- **Casting compatibility ladder**: automatic degradation through `direct → remux-ts → multi-profile H.264 transcoding`, with each profile verified to actually start playing via UPnP (`GetTransportInfo` + `GetPositionInfo`). Sources old TVs cannot handle (MKV, multiple audio tracks, HEVC/HDR) skip direct play and prefer lossless stream-copy remux.
- **Audio track fidelity**: casting URLs carry `audioStreamIndex`, preserving the track selected on the phone.
- **Casting controls**: a persistent control box (pause/resume/stop) while casting; after stopping, playback resumes locally at the TV's position.
- **Finplayer branding**: launch logo, launcher icon (all dpi) and the logo inside the WebView replaced with the project's own artwork.
- **PGS/VobSub subtitles**: experimental graphical subtitle rendering enabled by default in the WebView (existing manual settings are preserved).

### Fixed

- Transcode cold start returning HTTP 500 or an empty HTTP 200 body: added warm-up and same-profile retries so playback does not silently drop to a lower-quality profile.
- Old TVs stuck on "getting casting content information": resume now uses the server-side `startTimeTicks` instead of a SOAP Seek; stalled sessions degrade quickly; no `Stop` is sent between profile changes, because old TVs treat `Stop` as ending the whole cast.
- False stall detection: TVs that report `PLAYING` continuously while position telemetry stays at 0 are no longer treated as stalled and replayed repeatedly, while the "position frozen at the seek target" false-success case is still detected.
- Jumping to the end after stopping a cast: `GetPositionInfo` now reads `RelTime` (current progress) instead of `TrackDuration` (total length), and the resume position is clamped to the duration.
- Audio switching to English after casting: multi-track sources no longer use `static=true` direct play (Jellyfin ignores `audioStreamIndex` there) and go through remux with the audio track parameter instead.
- UI: casting dialogs unified to rounded semi-transparent backgrounds with centered text; fixed the Android 12 launch logo being clipped on both sides.
- Build: disabled the Gradle problems HTML report to avoid build failures caused by cross-path moves on external APFS volumes.

### Changed

- The version is managed by `jellyfin.version` in `gradle.properties` (`1.0.1`, i.e. `versionCode 1000199`).
- Gradle distribution download switched to a Tencent Cloud mirror.
- The casting implementation is split by `proprietary` / `libre` flavor, with shared logic isolated behind the `ICastPlayerFactory` interface; DLNA works in both flavors and does not require GMS.
