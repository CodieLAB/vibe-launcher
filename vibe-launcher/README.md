# Vibe Launcher

A free and open-source (MIT), tvOS-inspired home screen for Android TV, with no ads, ever. Plain
Java, about 2 MB, no background services; built for TVs with 1 to 2 GB of RAM. Android 8.0 (API 26) and newer, targets API 34 so Play Protect accepts it
on Android 11+ devices such as the NVIDIA Shield.

![Vibe Launcher home with a backdrop behind the dock](docs/new-home.jpg)

For what it does and how to install the ready-built APK, see the [main README](../README.md). This
page covers configuring, building and how the backdrops work.

## Configure

[`app/src/main/res/values/config.xml`](app/src/main/res/values/config.xml):

| Setting | What it does |
|---|---|
| `dock_apps` | Package names for the dock, in order. Missing apps are skipped. Can also be changed on the TV with long-press → Move. |
| `hidden_apps` | Packages never shown as tiles (e.g. the Live TV app, which the Inputs menu already offers). |
| `live_tv_package` | The TV's tuner app, offered at the end of the Inputs menu. Empty to omit. |
| `columns` | Default tiles per row (the dock has this many slots). On the TV, Vibe Launcher settings offers 4, 5 or 6, which overrides it. |

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

Build, install on the TV and make it the home screen in one go (uses the ADB client from
[`../debloat/tools`](../debloat/tools), which works where macOS blocks the stock `adb`):

```bash
TV_HOST=192.168.1.50 ./build-and-install.sh
```

Or by hand: `./gradlew assembleDebug`, then `adb install -r -g app/build/outputs/apk/debug/app-debug.apk`.

**Release builds** are signed with a key that never enters the repo. Put its location in
`local.properties` (ignored by git):

```properties
releaseStoreFile=/path/to/release.jks
releaseStorePassword=…
releaseKeyAlias=vibe-launcher
releaseKeyPassword=…
```

then `./gradlew assembleRelease`. Without these the release APK is built unsigned. Keep the key backed
up: APKs signed with a different key can't update an existing install. (A debug build can't update a
release install either, or the other way round; uninstall first.)

## Backdrops

When a dock app is focused, the launcher picks one image for it, a different one each time you come
back to Home:

| App | Source | Needs |
|---|---|---|
| Any app with home screen cards (Netflix, Apple TV, Prime Video, Jellyfin…) | The cards the app publishes to the system TV provider, read with the `READ_TV_LISTINGS` permission | Permission (granted with `adb install -g`, or asked for on first start) |
| Films and shows on those cards | The card's title looked up by exact match: a textless full-size backdrop plus the title's logo (or its name in Outfit when there is no logo). Otherwise the card's own art | Nothing: Stremio's free metadata (Cinemeta, metahub). With a TMDB key, TMDB instead |
| YouTube | SmartTube's cards: your own feed, at 1280×720 | [SmartTube](https://github.com/yuliskov/SmartTube) installed and signed in |
| YouTube without SmartTube | The most popular videos in the TV's country, refreshed every 4 hours (1 unit of the free 10,000 a day) | YouTube Data API key |
| Plex | Art of Continue Watching and recently added on your server, found through plex.tv and scaled by the server | Plex token |

Art narrower than 1280 px (Netflix publishes 853×480) is shown as a 2×2 wall of four cards; cards
with stickers baked in (Netflix's "Recently added", "Top 10") are only used in the wall. Apps with
nothing usable get an ambient glow in their icon's colour. Images are cached for a week, title matches
for a month. Stremio's metadata is not an official public API; if it stops answering, the cards' own
art is used.

Keys are entered in **Settings → Vibe Launcher settings**, on the TV or from a phone: the dialog shows
a QR code for a small page the launcher serves on your home network while the dialog is open. The
link carries a random code that changes every time, and the page shows saved keys hidden but for the
last four characters.

### What goes over the network

Only what the backdrops need: image downloads from the apps' own artwork servers; title art from
Stremio (`v3-cinemeta.strem.io`, `images.metahub.space`) or, with a key, TMDB (`api.themoviedb.org`,
`image.tmdb.org`); YouTube (`googleapis.com`, `i.ytimg.com`) and plex.tv plus your Plex server once
their keys are set. No analytics, no accounts, nothing else.

## Code map

| File | What it does |
|---|---|
| `MainActivity` | Home screen, dock and grid focus, top bar menus |
| `SettingsDialog` | Vibe Launcher settings: tiles per row, the keys and their ⓘ, the QR code |
| `AppAdapter`, `TileAnimator`, `DockDecoration` | Tiles, their animations and the dock band |
| `GlassView`, `FrostView` | The glass focus highlight and the frosted top bar (software blur; Android 9 has no live blur) |
| `Backdrops` | Picks, downloads, caches and decodes backdrops |
| `TitleArt`, `Tmdb`, `Stremio` | Clean backdrops and logos for films and shows by title: TMDB with a key, Stremio without |
| `YouTubePopular`, `Plex` | The keyed YouTube and Plex backdrop sources |
| `Http` | Plain GET requests for all of the above |
| `VibeSettings`, `PhoneLink` | Stored keys and the phone page with its QR code |
| `CpuInfo` | CPU use where the device allows it |
| `CrashGuard` | Falls back to the stock launcher's App info after repeated crashes |

Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md).
