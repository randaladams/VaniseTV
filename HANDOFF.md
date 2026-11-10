# VaniseTV: handoff

Everything a new session needs to continue this project. State as of **10/11/2026**, last feature commit `7aa293a` (pins, scans, search delay, click fix).

## What it is

A sideloaded Amazon Fire TV app (Fire OS 6.7.1.1 = Android 7.1, API 25) for watching free Brazilian live TV channels. The owner is Randy (GitHub `randaladams`); the end user is Portuguese-speaking. Native Kotlin with Android Views (no Compose), ExoPlayer (Media3 1.4.1). Installed with `adb install -r`, **never** through the Amazon Appstore.

- Repo: https://github.com/randaladams/VaniseTV (public, branch `main`)
- Latest APK (rolling): https://github.com/randaladams/VaniseTV/releases/download/latest-build/VaniseTV.apk
- Clickable screen preview (private claude.ai artifact, same URL for every version): https://claude.ai/artifact/HFz8Ruw2SKixtCRD8fmLnL. The source is `preview/vanisetv-preview.html`.

## How the user likes to work

- **Preview first for UI changes.** New screens, menus or layout changes go into `preview/vanisetv-preview.html` first and get published to the artifact above. The user tries it, then says "build it". Small fixes can go straight to code.
- **Discuss first when asked.** If the user says "discuss first", answer with a design and questions only, and don't write code.
- **Every visible text in English and Portuguese (pt-BR).** This is a hard requirement. `TranslationsTest` fails the build if a string lacks Portuguese, placeholders differ, or a layout hard-codes text. The preview must translate everything too: all strings live in its `TX.en` / `TX.pt` tables.
- **Commit and push only when asked.** "Yes, push it" or "build it" has meant commit, push, then wait for the GitHub build and report the result. Commit messages end with the `Co-Authored-By` line the harness provides.
- Plain, short explanations. The user is not an Android developer.

## Machine setup (Windows, user `radams1`)

| Thing | Location |
|---|---|
| Project | `C:\Users\radams1\dont sync\VaniseTV` |
| JDK 17 | `C:\Users\radams1\Android\jdk17` (system `JAVA_HOME` is Java 8, so always set it per command) |
| Android SDK (platform 34, build-tools 34, platform-tools) | `C:\Users\radams1\Android\sdk` |
| adb | `C:\Users\radams1\Android\sdk\platform-tools\adb.exe` |
| Release keystore | `C:\Users\radams1\Android\vanisetv-release.jks` (alias `vanisetv`). Password is in `keystore.properties` (gitignored). **Never print or commit it.** |
| `local.properties` | `sdk.dir=C:/Users/radams1/Android/sdk` (forward slashes; backslashes broke Gradle) |

Build and test (Git Bash):

```bash
cd "/c/Users/radams1/dont sync/VaniseTV"
export JAVA_HOME="C:/Users/radams1/Android/jdk17"
./gradlew --no-daemon -q testReleaseUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Tooling quirks seen in this session:
- The Bash tool fails on heredocs that contain an apostrophe (`'`). Write a Python script to the scratchpad and run it with `python -I` instead.
- No Node and no Pillow. PowerShell `System.Drawing` was used for image resizing.
- `gh` is not installed. GitHub status was checked through the public REST API with `curl` (`/repos/randaladams/VaniseTV/actions/runs`, `/releases/tags/latest-build`). Wait for a run with a background loop, not by polling in the foreground.

## CI / releases

`.github/workflows/build.yml` runs on push, PR, `workflow_dispatch` and `v*` tags. It tests, builds, and uploads the APK as an artifact.
- **Pushes and manual runs on `main`** move the `latest-build` tag and replace the APK on the "Latest build" pre-release.
- **`v*` tags** make a permanent release.
- **Signing:** repo secrets `VANISETV_KEYSTORE_BASE64` and `VANISETV_KEYSTORE_PASSWORD` (set by the user). Without them nothing is published.
- **Version codes:** CI builds use `100 + run_number` (`-PbuildNumber`), local builds use 2. After installing a CI build, a local build can't install over it without uninstalling first.
- Build 5 failed during a GitHub Actions outage, not a code problem.

## Architecture (app/src/main/java/com/vanisetv/app)

| File | Role |
|---|---|
| `App.kt` | Application: `Prefs`, `ChannelRepository`, `WatchStats`, click `SoundPool`, Coil image loader (disk cache for logos, SVG support), schedules background photos |
| `data/Channel.kt` | `Channel` (name, url, tvgId, logo, groups, quality, geoBlocked, not247, userAgent/referrer, `hidden`, `source` 1/2); `key = "$tvgId|$name"` |
| `data/M3u.kt` | Parse/write M3U. Strips "(720p)" and "[Geo-blocked]" from names. Our own attributes `vanise-source="1|2"` and `vanise-hidden="1"` live in the EXTINF line |
| `data/Sources.kt` | Two editable sources, defaults `https://iptv-org.github.io/iptv/countries/br.m3u` and `https://famelack.com/tv/br`. Either slot takes M3U or famelack; the format is **detected from content**. famelack pages map to `raw.githubusercontent.com/famelack/famelack-data/main/tv/raw/countries/<cc>.json`; YouTube-only entries are skipped. `merge()` removes duplicates by URL or normalized name (accents and punctuation removed), keeping the earlier source. `normalize()` is reused for search and sorting |
| `data/ChannelRepository.kt` | Local copy `files/channels.m3u`. First start downloads; refresh keeps hidden flags (by key, then URL). Geo-blocked channels start hidden. If any configured source fails, the refresh throws `SourceException(n)` and the old list is kept. `categories()`: All, Novelas (name or group contains "novela"), then groups by size, every list A–Z. PT group names live here |
| `data/Prefs.kt` | style `br`/`us`, lang `en`/`pt`, clicks, saverMinutes (3/10/60/0), source1/2, lastSourceStats, audioLang, subsLang (`off`/lang/`und`), subSize (`sys`/s/m/l) |
| `data/Pins.kt` | Home pins: newest first, max 5, a sixth drops the oldest (pure functions, `PinsTest`). Stored in `Prefs.pins` as channel keys |
| `data/StreamChecker.kt` | Quick (3 s) / Full (5 s) scan: 8 parallel GETs (headers only, player-like UA, EXTVLCOPT UA/referrer); dead = no answer in time or non-2xx. Cancellable; `ChannelRepository.setHidden(list)` saves once |
| `data/WatchStats.kt` | Seconds per channel per day, last 28 days; sessions under 30 s are ignored. Feeds the home top-3 tiles |
| `data/Backgrounds.kt` | WorkManager weekly job: 3 random landscape photos from Commons `Category:Quality_images_of_Brazil` at 1920 px, keeps 10, stores the artist for the credit. `current()` / `next()` are shared by home and the screen saver |
| `data/Net.kt` | OkHttp with UA `VaniseTV/1.0 (Fire TV; +https://github.com/randaladams/VaniseTV)`. Don't put the user's email in requests |
| `ui/BaseActivity.kt` | Per-app locale (`attachBaseContext`), recreates when the language changes, click sounds (off while playing), **moves arrow-key focus itself (`moveFocusSilently` in Ui.kt) so Fire TV's system navigation click never plays**, screen saver overlay (photo darkened, moving clock, next photo every 10 min, releases keep-screen-on after 1 h) |
| `ui/HomeActivity.kt` | Clock and date, **row of 5 pinned tiles**, row of top-3 tiles + LIVE TV (all go straight to full screen), Refresh dialog with Refresh now / Quick scan / Full scan (results list, Back stops a scan), Settings / Channel Editor / Refresh, exit confirmation (Back, then Back again exits), background and credit |
| `ui/GuideActivity.kt` | Live TV: info panel, preview player, compact list, category chip (Left/Right), **search** EditText (runs 3 s after the last key, or at once on the Search key; uses `Channel.searchKey`), **letter jump** on Rewind/FF, full screen (Up/Down zaps, banner), **☰ Menu "Channel options" panel (Pin/Unpin, subtitles, audio)** (keys handled manually in `dispatchKeyEvent`; `swallowBackUp` stops the closing Back from leaving full screen), subtitle style |
| `ui/TvPlayer.kt` | One ExoPlayer. Settles for 900 ms (zapping 700 ms) before tuning, stops the old stream first, 15 s stall watchdog with one retry, handles BEHIND_LIVE_WINDOW, applies EXTVLCOPT UA/referrer, records watch stats, track options/select/subtitlesOff, applies remembered preferences per channel |
| `ui/SettingsActivity.kt` | Sections TIME, LANG, SOUND, SAVER, SUBS, SOURCES (editable URLs through `TvDialog.input`, restore defaults), ABOUT |
| `ui/EditorActivity.kt` | All channels A–Z with show/hide; search; saves immediately |
| `ui/TvDialog.kt` | Remote-friendly dialog: `choices()` (2 side by side, 3+ stacked), determinate `progress()`, optional text input; moves focus silently too |
| `ui/Formats.kt`, `ui/Ui.kt` | US/BR time and date, day names; logo binding with an initials fallback, focus scaling |

Tests (`app/src/test`): `M3uTest`, `SourcesTest` (with snapshots `br.m3u` and `famelack-br.json` from 09/11/2026: 402 + 132 entries, merged 408), `TranslationsTest`. Plus `PinsTest`. **19 tests, all passing.**

## Feature list (all built and in CI build 9)

Home (clock and date, top 3 by the last 4 weeks, LIVE TV, Settings / Editor / Refresh, exit confirmation, weekly Wikimedia backgrounds) · Live TV (preview with sound, freeze protection, compact rows with a source badge, categories incl. Novelas, search, letter jump, full screen with zapping, ☰ subtitles and audio menu with remembered choices) · Channel Editor (show/hide, geo-blocked icon, source, search) · Refresh (two sources merged, hidden choices kept) · Settings (US/BR time style, English/Português, click sounds, screen saver 3 min/10 min/1 h/never, subtitle size following Fire TV or S/M/L, channel sources, About) · Screen saver (current photo, moving clock).

## Not verified / open items

- **Scan results depend on the network:** from the dev PC (US) about half of a 60-channel sample didn't answer at 3 s or 5 s; some streams only answer from Brazil.
- **Click fix** assumes Fire TV's click is Android's focus-navigation sound; fallback is Fire TV Settings › Display & Sounds › Audio › Navigation Sounds.
- **Nothing has been confirmed on a real Fire TV yet.** All checks were builds, unit tests and the browser preview. The user is about to sideload build 9.
- **Subtitle and audio tracks** depend on each stream. Expect most channels to have one audio track and no subtitles. Suggested test channels: AXN or AMC Latin America (audio), news channels (CC). Undeclared CEA-608 captions won't appear.
- **On-screen keyboard** (search, source URLs): uses `flagNoExtractUi|flagNoFullscreen`, but some Fire OS 6 keyboards still go full screen.
- **Remote keys assumed:** ☰ = `KEYCODE_MENU`, ⏪/⏩ = `KEYCODE_MEDIA_REWIND` / `KEYCODE_MEDIA_FAST_FORWARD`.
- **Older Fire OS certificate store:** HTTPS streams with newer certificates could fail. Conscrypt was considered and not added.
- **Ideas discussed but not built:** Alexa voice search (not possible for sideloaded apps); a famelack-only category; plurals ("1 channels" in the category chip; search uses "(n)" to avoid this).

## Preview notes

`preview/vanisetv-preview.html` is a self-contained 1920×1080 TV mockup, scaled. Keys: arrows, Enter = OK, Esc/Backspace = Back, M = ☰ Menu, PageUp/PageDown = ⏪/⏩. The channel data and track lists are examples. When editing it: every string goes in `TX.en` and `TX.pt`, never write `*/` inside a JS comment (it broke the script once), test with the built-in browser pane (`file:///…/preview/vanisetv-preview.html`), then publish with the Artifact tool to the same URL. Locally the `[hidden]` reset is missing, so overlays can look visible in a plain file view; the page sets `.stage [hidden]{display:none!important}` for that.
