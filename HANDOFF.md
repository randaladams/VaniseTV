# VaniseTV: handoff

Everything a new session needs to continue this project. State as of **11/11/2026**: last commit is this handoff, on top of `2d3ec99` (Channel Editor filter). GitHub builds 13 to 16 all green; `latest-build` holds **build 16**, which has every change below. Nothing is uncommitted; `main` is in sync with GitHub.

## Resuming in a new session

1. On the new workstation: `git clone https://github.com/randaladams/VaniseTV.git` (or `git pull` in an existing clone).
2. Open a Claude Code session in that folder (`CLAUDE.md` loads automatically) and say: *"Read HANDOFF.md and continue VaniseTV."*
3. Read "Status and open items" below, then ask the user what they'd like to do next.

No local build tools are needed: every push to `main` is tested, built, signed and published by GitHub Actions (see "CI / releases"). Local builds need the extra setup in "Machine setup".

## What it is

A sideloaded Amazon Fire TV app (Fire OS 6.7.1.1 = Android 7.1, API 25, device AFTMM) for watching free Brazilian live TV channels. The owner's GitHub account is `randaladams`; the app's menus are in English and Portuguese. Native Kotlin with Android Views (no Compose), ExoPlayer (Media3 1.4.1). Installed with `adb install -r`, **never** through the Amazon Appstore.

- Repo: https://github.com/randaladams/VaniseTV (public, branch `main`)
- Latest APK (rolling): https://github.com/randaladams/VaniseTV/releases/download/latest-build/VaniseTV.apk
- Clickable screen preview (private claude.ai artifact, same URL for every version): https://claude.ai/artifact/HFz8Ruw2SKixtCRD8fmLnL. The source is `preview/vanisetv-preview.html`.
- **Screen size on the TV is 960×540 dp.** Layouts must fit that height; the Settings menu and the Refresh dialog were clipped before build 13.

## How the user likes to work

- **Preview first for UI changes.** New screens, menus or layout changes go into `preview/vanisetv-preview.html` first and get published to the artifact above. The user tries it, then says "build it". Small fixes can go straight to code.
- **Discuss first when asked.** If the user says "discuss", answer with a design and questions only, and write code only after they agree.
- **Every visible text in English and Portuguese (pt-BR).** This is a hard requirement. `TranslationsTest` fails the build if a string lacks Portuguese, placeholders differ, or a layout hard-codes text. The preview must translate everything too: all strings live in its `TX.en` / `TX.pt` tables.
- **Commit and push only when asked.** "Build it" or "yes, push it" means commit, push, wait for the GitHub build and report the result. "Write and commit" means commit only. Commit messages end with the `Co-Authored-By` line the harness provides.
- The user tests on the TV and reports back with phone photos of the screen.
- Plain, short explanations. The user is not an Android developer.

## Machine setup

Two workstations have been used:

| | Original PC (user `radams1`) | Second PC (user `Randy`) |
|---|---|---|
| Project | `C:\Users\radams1\dont sync\VaniseTV` | `C:\Users\Randy\Desktop\VaniseTV\VaniseTV` (the inner folder; see note) |
| JDK 17, Android SDK, adb | `C:\Users\radams1\Android\jdk17`, `...\Android\sdk` | **none**: all builds ran on GitHub |
| Release keystore | `C:\Users\radams1\Android\vanisetv-release.jks` (alias `vanisetv`) | not present |
| Python | yes | yes (3.12) |

Note on the second PC: `C:\Users\Randy\Desktop\VaniseTV` is an old clone whose tracked files show as deleted; the real, up-to-date clone is the `VaniseTV` folder inside it. Don't commit from the outer one.

For a **local build** on any machine you need JDK 17, Android SDK (platform 34, build-tools 34, platform-tools), `local.properties` with `sdk.dir=C:/path/to/sdk` (forward slashes; backslashes broke Gradle) and, for a signed release, the keystore plus `keystore.properties`. Both properties files are gitignored. **Never print or commit the keystore or its password.** Then (Git Bash):

```bash
export JAVA_HOME="C:/path/to/jdk17"     # system Java is often 8; set it per command
./gradlew --no-daemon -q testReleaseUnitTest assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Tooling notes:
- `gh` is not installed. GitHub status is checked through the public REST API with `curl` (`/repos/randaladams/VaniseTV/actions/runs`, `/actions/runs/<id>/jobs`, `/check-runs/<job id>/annotations`, `/releases/tags/latest-build`). Wait for a run with a background loop (`run_in_background`), not by polling in the foreground.
- No Node and no Pillow. For text edits with accents or exact line endings, a small `python -I` script works well.
- `cd` in a Bash call can change the session's working directory; use absolute paths.

## CI / releases

`.github/workflows/build.yml` runs on push, PR, `workflow_dispatch` and `v*` tags. It runs the unit tests, builds, and uploads the APK as an artifact (`actions/upload-artifact@v7`).
- **Pushes and manual runs on `main`** move the `latest-build` tag and replace the APK on the "Latest build" pre-release (`softprops/action-gh-release@v3`).
- **`v*` tags** make a permanent release.
- **Signing:** repo secrets `VANISETV_KEYSTORE_BASE64` and `VANISETV_KEYSTORE_PASSWORD` (set by the user). Without them nothing is published.
- **Version codes:** CI builds use `100 + run_number` (`-PbuildNumber`), local builds use 2. After installing a CI build, a local build can't install over it without uninstalling first.
- Both actions were moved to their Node 24 versions on 11/11/2026, which removed GitHub's Node.js 20 warning. The only remaining notice is that `ubuntu-latest` moves to Ubuntu 26; no action needed so far.

## Architecture (app/src/main/java/com/vanisetv/app)

| File | Role |
|---|---|
| `App.kt` | Application: `Prefs`, `ChannelRepository`, `WatchStats`, click `SoundPool`, Coil image loader (disk cache for logos, SVG support), schedules background photos |
| `data/Channel.kt` | `Channel` (name, url, tvgId, logo, groups, quality, geoBlocked, not247, userAgent/referrer, `hidden`, `source` 1/2); `key = "$tvgId|$name"` |
| `data/M3u.kt` | Parse/write M3U. Strips "(720p)" and "[Geo-blocked]" from names. Our own attributes `vanise-source="1|2"` and `vanise-hidden="1"` live in the EXTINF line |
| `data/Sources.kt` | Two editable sources, defaults `https://iptv-org.github.io/iptv/countries/br.m3u` and `https://famelack.com/tv/br`. Either slot takes M3U or famelack; the format is **detected from content**. famelack pages map to `raw.githubusercontent.com/famelack/famelack-data/main/tv/countries/<cc>.json` (famelack removed the old `tv/raw/` folder on 10/11/2026, which broke Refresh until build 13). YouTube-only entries are skipped. `merge()` removes duplicates by URL or normalized name, keeping the earlier source. `normalize()` is reused for search and sorting |
| `data/ChannelRepository.kt` | Local copy `files/channels.m3u`. First start downloads; refresh keeps hidden flags (by key, then URL). Geo-blocked channels start hidden. If any configured source fails, the refresh throws `SourceException(n)` and the old list is kept. `categories()`: All, Novelas, then groups by size, every list A–Z. PT group names live here |
| `data/Prefs.kt` | style `br`/`us`, lang `en`/`pt`, clicks, saverMinutes (3/10/60/0), source1/2, lastSourceStats, audioLang, subsLang (`off`/lang/`und`), subSize (`sys`/s/m/l), pins |
| `data/Pins.kt` | Home pins: newest first, max 5, a sixth drops the oldest (pure functions, `PinsTest`) |
| `data/ShowFilter.kt` | Channel Editor filter ALL / SHOWN / HIDDEN; `matches(hidden, switched)` keeps a just-switched channel listed (`ShowFilterTest`) |
| `data/StreamChecker.kt` | Quick (3 s) / Full (5 s) scan: 8 parallel GETs (headers only, player-like UA); dead = no answer in time or non-2xx. Cancellable |
| `data/WatchStats.kt` | Seconds per channel per day, last 28 days; sessions under 30 s are ignored. Feeds the home top-3 tiles |
| `data/Backgrounds.kt` | WorkManager weekly job: 3 random landscape photos from Commons `Category:Quality_images_of_Brazil`, keeps 10, stores the artist for the credit |
| `data/Net.kt` | OkHttp with UA `VaniseTV/1.0 (Fire TV; +https://github.com/randaladams/VaniseTV)`. Don't put the user's email in requests |
| `ui/BaseActivity.kt` | Per-app locale, recreates when the language changes, click sounds (off while playing), **moves arrow-key focus itself (`moveFocusSilently` in Ui.kt) so Fire TV's own navigation click never plays** (confirmed working on the TV), screen saver overlay |
| `ui/HomeActivity.kt` | Clock and date, row of 5 pinned tiles, top-3 tiles + LIVE TV, Refresh dialog with Refresh now / Quick scan / Full scan / Cancel, Settings / Channel Editor / Refresh, exit confirmation, background and credit |
| `ui/GuideActivity.kt` | Live TV. **Opens on the first channel and starts it in the preview; Up/Down, letter jump and the Search key preview the row they land on** (`previewChannel`, `previewFocusedRow`). **Up/Down limited to one step per 400 ms** (`MOVE_LIMIT_MS`, also in full screen). One OK = full screen. Category chip (Left/Right), search (3 s after the last key), letter jump on ⏪/⏩, full screen with banner (`armBannerHide` restarts its 4 s timer, also after the ☰ menu closes), ☰ Channel options (pin, subtitles, audio; keys handled in `dispatchKeyEvent`) |
| `ui/TvPlayer.kt` | One ExoPlayer, so two streams can never play at once. Waits 900 ms (zapping 700 ms) for the remote to settle, stops the old stream first, 15 s stall watchdog with one retry, handles BEHIND_LIVE_WINDOW, records watch stats, track selection |
| `ui/SettingsActivity.kt` | Sections TIME, LANG, SOUND, SAVER, SUBS, SOURCES, ABOUT. `clearingOptions` guard stops the panel being built twice (the "doubled options" bug); menu rows are compact so all 7 fit |
| `ui/EditorActivity.kt` | All channels A–Z with show/hide, saves immediately. **All / Shown / Hidden chips with counts** (`FilterChip` style, `bg_filter_chip`, `filter_text`), combined with search; opens on All |
| `ui/TvDialog.kt` | Remote-friendly dialog: 2 buttons side by side, 3 stacked, **4 or more in a 2-column grid**; the message sits in a ScrollView that shrinks first so buttons stay on screen; progress bar; text input |
| `ui/Formats.kt`, `ui/Ui.kt` | US/BR time and date, day names; logo binding with an initials fallback, focus scaling |

Tests (`app/src/test`): `M3uTest`, `SourcesTest` (snapshots `br.m3u` and `famelack-br.json` from 09/11/2026), `TranslationsTest`, `PinsTest`, `ShowFilterTest`. **22 tests, all passing in build 16.**

## Commits this session (10–11/11/2026)

- `a0270b8` Fixes from the user's TV report: famelack URL (Refresh failed), doubled Settings options, About row cut off, Cancel off screen in the Refresh dialog, banner stuck after the ☰ menu.
- `370faea` Live TV auto-preview and 0.4 s movement limit.
- `56c816d` CI actions to Node 24 versions.
- `2d3ec99` Channel Editor All / Shown / Hidden filter.

## Status and open items

**All known issues are fixed: the user confirmed on 11/11/2026 that build 16 works on the TV.** That covers Refresh now (Source 2 downloads again), Settings (no doubled options, About visible), the Refresh dialog's four buttons, the full-screen banner hiding after ☰, Live TV auto-preview and Up/Down pacing, and the Channel Editor filter chips and search in both languages. Earlier confirmations (build 12): search no longer freezes, click sounds On/Off works, home layout fits, pinning from ☰ works.

No work is in progress.

Other notes:
- **Many channels looked dead in a scan.** Scan-hidden channels stay hidden after Refresh by design; they can be shown again in the Channel Editor (now easy with the Hidden filter). Scan results depend on the network; some streams only answer from Brazil or slowly.
- **The preview's Live TV screen still shows the old behaviour** (OK to start a channel, no movement limit). Update it before the next Live TV UI change.
- **What Claude can test:** only builds, unit tests and the browser preview. Anything about real playback or the remote comes from the user.
- **Subtitle and audio tracks** depend on each stream; most channels have one audio track and no subtitles.
- **On-screen keyboard:** uses `flagNoExtractUi|flagNoFullscreen`, but some Fire OS 6 keyboards still go full screen.
- **Remote keys assumed:** ☰ = `KEYCODE_MENU`, ⏪/⏩ = `KEYCODE_MEDIA_REWIND` / `KEYCODE_MEDIA_FAST_FORWARD`.
- **Older Fire OS certificate store:** HTTPS streams with newer certificates could fail. Conscrypt was considered and not added.
- **Ideas discussed but not built:** Alexa voice search (not possible for sideloaded apps); a famelack-only category; plurals ("1 channels" in the category chip); keeping the other source's channels when one source fails to download.

## Preview notes

`preview/vanisetv-preview.html` is a self-contained 1920×1080 TV mockup, scaled. Keys: arrows, Enter = OK, Esc/Backspace = Back, M = ☰ Menu, PageUp/PageDown = ⏪/⏩. The channel data and track lists are examples. When editing it: every string goes in `TX.en` and `TX.pt`, never write `*/` inside a JS comment, test with the built-in browser pane (`file:///…/preview/vanisetv-preview.html`), then publish with the Artifact tool to the same URL. From a new session the artifact must be read (`action: "read"`) before it can be republished. Locally the `[hidden]` reset is missing, so the page sets `.stage [hidden]{display:none!important}`.
