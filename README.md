# VaniseTV

Live TV app for Amazon Fire TV (Fire OS 6 and later), built for Brazilian channels from
[iptv-org](https://github.com/iptv-org/iptv). Sideloaded with adb, not published in the Amazon Appstore.

## What it does

- **Home:** large clock (top left) and date (top right). Your 3 most-watched channels from the last 4 weeks, plus LIVE TV. Below the line: Settings, Channel Editor, Refresh Channels. Back asks before exiting; Back again exits.
- **Live TV:** channel info top left, live picture with sound top right, channel list below. OK plays a channel in the preview box; OK again goes full screen. In full screen, Up/Down changes channel and Back returns to the list. Left/Right on the category chip changes category (including **Novelas**).
- **Freeze protection:** a stream only starts after the remote has been still for 0.9 s (0.7 s when zapping in full screen); only one player exists and the old stream is stopped first. Streams that fail or stall for 15 s are retried once, then reported as unavailable.
- **Channel list:** combined from two sources (Settings › Channel sources), stored as `channels.m3u` in the app's private storage and used until Refresh Channels:
  - Source 1 default: `https://iptv-org.github.io/iptv/countries/br.m3u`
  - Source 2 default: `https://famelack.com/tv/br` (read from famelack's data file on GitHub)
  - Either slot accepts an M3U playlist link or a famelack country page; the format is detected from the download. An empty slot is ignored.
  - Duplicates (same stream URL, or same name ignoring accents/punctuation) are dropped; the earlier source's copy is kept. famelack entries that only have YouTube links are skipped because the player can't play them.
  - Each entry in the file carries `vanise-source="1"` or `"2"`; hidden channels also carry `vanise-hidden="1"`. Geo-blocked channels start hidden, and Refresh Channels keeps your hidden choices. If any configured source fails to download, the refresh is cancelled and the current list is kept.
- **Logos:** taken from each channel's `tvg-logo` and cached on the device; coloured initials appear when a logo is missing.
- **Backgrounds:** once a week, 3 random landscape photos from Wikimedia Commons (`Category:Quality_images_of_Brazil`) are downloaded at 1920 px. At most 10 are kept. The photographer is credited in the corner.
- **Settings:** time & date style (United States or Brazil), language (English or Português), remote click sounds, screen saver (3 min, 10 min, 1 hour, never), About.

## Build

Requirements: JDK 17 and the Android SDK (platform 34, build-tools 34). `local.properties` must point at the SDK:

```
sdk.dir=C:/Users/<you>/Android/sdk
```

Release signing reads `keystore.properties` (not committed). Without it the release build is signed with the debug key.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

Unit tests (playlist parsing): `./gradlew testReleaseUnitTest`

## Install on the Fire TV stick

1. On the Fire TV: **Settings › My Fire TV › Developer options › ADB debugging: ON** (and **Apps from Unknown Sources: ON**).
2. Find its IP address: **Settings › My Fire TV › About › Network**.
3. From the PC (same network):

```bash
adb connect 192.168.1.50:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

The first `adb connect` shows a prompt on the TV; accept it. VaniseTV then appears under **Your Apps & Channels**.

## Icons

Source art is in `art/`. The launcher banner (`res/drawable-xhdpi/banner.png`, 320×180) and app icons (`res/mipmap-*/ic_launcher.png`) are generated from it.
