# Vibe Launcher

A clean, tvOS-inspired home screen for Android TV. **Free and open source** (MIT), and **no ads,
ever**: no accounts, no tracking, no paid tier.

| Stock Sony home screen | Vibe Launcher |
|---|---|
| ![Stock Google TV home on a Sony XBR-940E, with a sponsored carousel and a Shop tab](vibe-launcher/docs/stock-home-sony.jpg) | ![Vibe Launcher on the same TV, with a full-screen backdrop behind the dock](vibe-launcher/docs/new-home.jpg) |

**Just download the APK from [Releases](https://github.com/maydings/vibe-launcher/releases/latest)
and install it** (see [Install](#install)). The source and a build script are in
[`vibe-launcher/`](vibe-launcher/).

> **This repo is the launcher only.** To debloat your TV (turn off the ads, tracking and preinstalled
> extras), use the project this one is forked from,
> [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack): its tools and tested per-model lists
> do that over the network, and can undo it. See [Before you install](#before-you-install).

## What it does

A small, fast home screen built for TVs with 1 to 2 GB of RAM. Android 8.0 and newer. Free and open
source: every feature is in the APK, nothing is behind a purchase.

![The app grid with the glass focus highlight](vibe-launcher/docs/launcher-grid.jpg)

**The home screen**

- **Dock and grid.** Your favourite apps (four, or up to six) sit in a dock at the bottom. Press Down and the rest of
  your apps slide up in a grid. A single glass highlight springs from tile to tile. Press Home on
  the home screen to jump back to the top.
- **Backdrops.** The focused dock app fills the screen behind it with artwork from what it is
  actually offering you:
  - the cards apps publish for the TV home screen (Netflix, Apple TV, Prime Video, Jellyfin and
    others), read the same way Google's own launcher does, and asked for the same way, so apps such
    as Prime Video publish theirs without the stock launcher;
  - the films and shows on those cards are shown with a clean, full-size backdrop and the title's
    own logo instead of the apps' art (no "Recently added" stickers): out of the box from Stremio's
    free metadata, or from **TMDB** with a free key;
  - **YouTube** shows your own feed through [SmartTube](https://github.com/yuliskov/SmartTube)'s
    cards, or popular videos with a YouTube Data API key;
  - **Plex** shows Continue Watching and recently added from your own server with a Plex token;
  - low-resolution art becomes a 2×2 wall instead of one blurry still; apps with nothing to show get
    an ambient glow in their own colour.
- **Screensaver.** "Vibe Launcher posters" shows the same backdrops full screen, slowly drifting
  and cross-fading every 20 seconds, with a large clock and each film's logo. It uses only pictures
  already on the TV, no network. Pick it in the TV's screen saver settings (on Android TV:
  Settings → Device Preferences → Screen saver).
- **Frosted top bar** with the clock, date and three pills: **Inputs**, **Network** (each interface
  and its IP address) and **Settings**.
- **Inputs** lists HDMI, AV and Live TV, with whether something is plugged in (the pill is hidden on
  devices without inputs). Devices that announce themselves over HDMI-CEC show by name under their
  port: "HDMI 1 · PlayStation 5", or for an Apple TV and a PS5 behind a switch, HDMI 2 with a row
  for each. Picking a device wakes it, and an AV receiver or switch that supports CEC switching turns to
  it. Names and hidden inputs set in the TV's own input settings carry over.

| ![The Inputs menu: HDMI 2 with a PlayStation 5 and an Apple TV behind a switch, found over HDMI-CEC](vibe-launcher/docs/inputs-menu.jpg) | ![The screensaver: a film's backdrop and logo with a large clock](vibe-launcher/docs/screensaver.jpg) |
|---|---|

**Settings**

- The gear shows live **RAM** use (and CPU on devices that allow apps to read it), **Vibe Launcher
  settings** and **Android settings**.

  ![The gear menu with live RAM use](vibe-launcher/docs/settings-menu.jpg)

- Vibe Launcher settings have two tabs. **General**: **tiles per row** (4, the default, 5 or 6; tiles
  shrink to fit and the dock holds as many apps), **language** and the **software version** with
  **Check for updates**.
- **Language**: Vibe Launcher follows the TV's language, or you can pick one of 50, each listed in its
  own name. The date and clock follow it too. The translations are machine-made: corrections are
  welcome (`vibe-launcher/app/src/main/res/values-*/strings.xml`).
- **API keys**: the TMDB, YouTube and Plex keys. Each has an ⓘ explaining what it does and where to get
  it. Typing keys with a remote is no fun, so the tab shows a **QR code**: scan it with your phone and
  paste the keys there. Saved keys are shown hidden but for their last four characters, on the TV and
  on the phone. Keys stay on the TV; the launcher never ships with any.
- **Updates**: once a day Vibe Launcher checks this project's releases. When there's a new version, a
  green dot appears in the Settings gear and the gear menu offers **Update to** it, which shows what's
  new with **Install** and **Cancel**. The download is checked against the release's SHA-256 before
  Android's own installer asks you to confirm. The first time, Android asks you to allow Vibe Launcher
  to install apps. Once the update is in, Home comes back by itself.

**Managing apps**

- Long-press OK on a tile to **move** it (into and out of the dock), **hide** it, **uninstall** it or
  open **App info**. Hidden apps can be brought back from the same menu.
- If the launcher ever crashes twice in two minutes, it opens App info for the stock launcher so one
  press of Enable gets you a working home screen back.

## Before you install

Vibe Launcher replaces the home screen, and only that. Two things are up to you:

- **Debloat your TV.** The ads and bloat don't live only in the stock home screen: TV makers and
  Google also run promoted-app installers, viewing-data tracking, demo modes and the services behind
  sponsored rows, and they keep running whatever the home screen is. Turn them off over adb first.
  This repo doesn't do that: use [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack), the
  project it's forked from, which keeps tested lists for well over a hundred TV models. Disable
  packages rather than uninstall them, so everything can be undone.
- **Turn off Google's launcher and Google's setup app** for Vibe Launcher to stay the default home
  screen. Both are system apps that answer the Home button, and Android lets them outrank any app you
  install: while either is on, Home can keep opening it (or Android's "choose a home app" prompt),
  even with Vibe Launcher set as the default. No app is allowed to disable another, so this takes a
  computer, once (see [With a computer](#with-a-computer-adb)):
  - Google's launcher: `com.google.android.tvlauncher` on Android TV,
    `com.google.android.apps.tv.launcherx` on Google TV (`com.google.android.leanbacklauncher` on
    older sets).
  - Google's setup app: `com.google.android.tungsten.setupwraith`. Two things to know first:
    - **Wi-Fi:** a network saved during the TV's first-time setup belongs to this app, and Android
      removes an app's saved networks when it's disabled, taking the TV offline. Check with
      `adb shell dumpsys wifi | grep -o 'cname=[^ ]*'`: if it names setupwraith, forget the network in
      Settings and join it again (or use Ethernet) before disabling.
    - **Casting:** on some Google TVs, casting from a phone then fails with "Untrusted device". If you
      cast, turn it back on.

## Install

Vibe Launcher is just an APK: download it and install it. No account, no build tools. The
latest version is always at

**https://github.com/maydings/vibe-launcher/releases/latest/download/vibe-launcher.apk**

### On the TV, no computer needed

1. From the Play Store, install a downloader or file manager, e.g. **Downloader** (by AFTVnews) or
   **X-plore**.
2. Open the link above in it, then open the downloaded APK. Android asks once to allow that app to
   install unknown apps; allow it.
3. Open Vibe Launcher from your apps and allow the two permissions it asks for: TV listings (the
   apps' home screen cards, for backdrops) and location (Android only reveals the Wi-Fi network's
   name with it).
4. Make it your home screen. Right after the permissions, Vibe Launcher offers this itself where the
   TV allows it: Android's "default Home app" dialog on Android 10 and newer, or the "Always / Just
   once" chooser on Android 9 while no Home app has been chosen yet. It's also under the gear menu →
   **Make Vibe Launcher your Home** whenever it isn't.

   That isn't always enough: while Google's launcher or setup app is on, Home can keep going to them
   (see [Before you install](#before-you-install)). Until you turn them off, you can still open Vibe
   Launcher from your apps.

### With a computer (adb)

1. Turn on developer options and USB/network debugging on the TV (Settings → Device Preferences →
   About → press OK on **Build** seven times → back → Developer options).
2. Check the Wi-Fi note under [Before you install](#before-you-install), then download the APK and
   install it from a computer on the same network, in this order:

   ```bash
   adb connect <tv-ip>:5555
   adb install -g vibe-launcher.apk      # -g grants its permissions up front
   adb shell am start -n com.maydings.vibelauncher/.MainActivity
   # Google's launcher (on Google TV: com.google.android.apps.tv.launcherx) and Google's setup app
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
   # Always last: Android forgets the chosen home app whenever one is enabled or disabled
   adb shell cmd package set-home-activity com.maydings.vibelauncher/.MainActivity
   ```

3. Press Home. To bring the stock launcher or setup app back,
   `adb shell pm enable --user 0 <package>`.

Build it yourself, configure the dock and hidden apps, and the details of each backdrop source:
[`vibe-launcher/README.md`](vibe-launcher/README.md).

## Tested on

| Device | Android |
|---|---|
| Sony Bravia XBR-940E | 9 |
| Sony Bravia 900F | 9 |
| NVIDIA Shield TV (2017) | 11 |

It should run on any Android TV or Google TV with Android 8 or newer.

## Credits and licence

Started as a fork of [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) and rewritten
since. MIT licence, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font
License (`vibe-launcher/FONT-LICENSE-OFL.txt`). Backdrops use the TMDB API but are not endorsed or
certified by TMDB.
