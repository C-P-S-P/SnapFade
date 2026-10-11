# SnapFade

**Temporary screenshots for Android.** Take screenshots the normal way (Power + Volume Down) and they go to your clipboard instead of cluttering your gallery. They self-destruct automatically unless you save them.

## Download

[**SnapFade v0.1 APK**](https://github.com/Sridhar1030/SnapFade/releases/latest) — 2.2MB, Android 11+

## Why

Every screenshot you take gets saved permanently. Most are throwaway: a quick share, a reference, a one-time paste. SnapFade makes screenshots ephemeral by default and permanent only when you choose.

## How it works

```
Power+VolDown → system screenshot → SnapFade intercepts → clipboard + auto-delete
```

1. You take a screenshot as usual. The system writes it to `Pictures/Screenshots`.
2. SnapFade (a resident accessibility service watching MediaStore) copies it to its private cache, puts it on the clipboard as an image, and **deletes the gallery copy** within ~1 second.
3. A minimal popup appears: **Copied | Save | X** (auto-dismisses in 6s).
   - **Save** writes it back to `Pictures/Screenshots` permanently.
   - Otherwise it auto-deletes after your chosen expiry time.
4. The app's main screen lists every shot still alive with a countdown timer. Per row:
   - **Tap** to copy it to clipboard again
   - **Share** to open the system share sheet
   - **Save** to write it to the gallery
   - **Trash** to delete it immediately
5. **Custom expiry:** choose 1h / 3h / 6h / 12h / 24h from the main screen.

## Setup

1. Install the APK and open SnapFade.
2. **Enable the accessibility service:** Settings > Accessibility > SnapFade.
   - On Android 13+ sideloads: if the toggle is greyed out, go to App Info > ... > **Allow restricted settings** first.
3. **Grant "All files access":** needed to delete screenshots from the gallery without a confirmation dialog each time.

The main screen shows which permissions are still missing.

## Build

```bash
# Requires JDK 17 and Android SDK 35
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Enable service via adb (skips restricted settings block)
adb shell settings put secure enabled_accessibility_services app.snapfade/app.snapfade.ShotService
adb shell appops set app.snapfade MANAGE_EXTERNAL_STORAGE allow
```

**minSdk 30** (Android 11). Tested on vivo V2303 (Android 15, Funtouch 15).

## Architecture

```
ShotService (AccessibilityService)
├── ContentObserver on MediaStore  → detects new screenshots
├── grab()                         → cache file, delete gallery copy, clipboard, schedule expiry
└── showOverlay()                  → TYPE_ACCESSIBILITY_OVERLAY popup (Save / dismiss)

Shots (object)
├── copyToClipboard()  → FileProvider URI + grantUriPermission to systemui
├── share()            → ACTION_SEND intent with image URI
├── scheduleExpiry()   → WorkManager OneTimeWorkRequest per file (configurable TTL)
├── saveToGallery()    → MediaStore insert with IS_PENDING pattern
├── remove()           → delete + cancel worker + clear clipboard
└── sweep()            → delete all expired shots

ExpireWorker           → per-file worker, fires after configured expiry
MainActivity           → ListView of cached shots, live-refreshing via FileObserver
```

## Design decisions

- **MediaStore observer, not screenshot API:** Android doesn't let apps intercept Power+VolDown. The accessibility screenshot API (`takeScreenshot()`) requires a separate trigger. Watching MediaStore catches the result of the normal combo with ~1s latency.
- **AccessibilityService as host:** keeps the process alive and provides `TYPE_ACCESSIBILITY_OVERLAY` window token (no `SYSTEM_ALERT_WINDOW` permission needed).
- **`MANAGE_EXTERNAL_STORAGE`:** required to delete other apps' screenshots without per-file confirmation. Fine for sideloading; Google Play would reject this.
- **Per-file WorkManager jobs:** each screenshot gets its own timer. More precise than a single periodic sweep.
- **Overlay at screen top:** Vivo (and some other OEMs) show their own screenshot preview at the bottom.
- **R8 minification:** shrinks APK from 7.4MB to 2.2MB by stripping unused AndroidX classes.

## Limitations

- Sideload only. `MANAGE_EXTERNAL_STORAGE` won't pass Play Store review for this use case.
- Detection matches `RELATIVE_PATH LIKE '%Screenshot%'` or filenames starting with `Screenshot`. Covers stock Android, Samsung, Xiaomi, vivo. May need tuning for unusual OEMs.
- Pasting works in apps that accept image content (Gmail, WhatsApp, Telegram, Slack, Keep). Plain text fields ignore it.
- Background apps can't read the clipboard on Android 10+, so after expiry the clip entry may still appear but the backing file is gone.

## License

MIT
