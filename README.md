# Vibe Launcher

A clean, tvOS-inspired home screen for Android TV, and the tools to strip the ads and bloat out of
the TV it runs on. **Free and open source** (MIT), and **no ads, ever**: no accounts, no tracking, no paid tier.

| Stock Sony home screen | Vibe Launcher |
|---|---|
| ![Stock Google TV home on a Sony XBR-940E, with a sponsored carousel and a Shop tab](debloat/docs/stock-home-sony.jpg) | ![Vibe Launcher on the same TV, with a full-screen backdrop behind the dock](vibe-launcher/docs/new-home.jpg) |

The repo has two parts:

| Folder | What it is |
|---|---|
| [`vibe-launcher/`](vibe-launcher/) | The launcher: source, build script and docs. Ready-built APKs are on the [Releases](https://github.com/maydings/vibe-launcher/releases/latest) page. |
| [`debloat/`](debloat/) | Debloating any Android TV over ADB: the prompt for Claude Code (or another coding assistant), ADB tools, and per-TV lists. |

---

## Vibe Launcher

A small, fast home screen built for TVs with 1 to 2 GB of RAM. Android 8.0 and newer. Free and open
source: every feature is in the APK, nothing is behind a purchase.

![The app grid with the glass focus highlight](vibe-launcher/docs/launcher-grid.jpg)

**The home screen**

- **Dock and grid.** Your four favourite apps sit in a dock at the bottom. Press Down and the rest of
  your apps slide up in a grid. A single glass highlight springs from tile to tile.
- **Backdrops.** The focused dock app fills the screen behind it with artwork from what it is
  actually offering you:
  - the cards apps publish for the TV home screen (Netflix, Apple TV, Prime Video, Jellyfin and
    others), read the same way Google's own launcher does;
  - with a free **TMDB** key, the films and shows on those cards are shown with TMDB's clean,
    full-size backdrops instead of the apps' own art (no logos, no "Recently added" stickers);
  - **YouTube** shows your own feed through [SmartTube](https://github.com/yuliskov/SmartTube)'s
    cards, or popular videos with a YouTube Data API key;
  - **Plex** shows Continue Watching and recently added from your own server with a Plex token;
  - low-resolution art becomes a 2×2 wall instead of one blurry still; apps with nothing to show get
    an ambient glow in their own colour.
- **Frosted top bar** with the clock, date and three pills: **Inputs** (HDMI, AV, Live TV; hidden
  on devices without inputs), **Network** (each interface and its IP address) and **Settings**.

**Settings**

- The gear shows live **RAM** use (and CPU on devices that allow apps to read it), **Vibe Launcher
  settings** and **Android settings**.
- Vibe Launcher settings hold the TMDB, YouTube and Plex keys. Each has an ⓘ explaining what it does
  and where to get it. Typing keys with a remote is no fun, so the screen shows a **QR code**: scan
  it with your phone and paste the keys there. Saved keys are shown hidden but for their last four
  characters.
- Keys stay on the TV; the launcher never ships with any.

**Managing apps**

- Long-press OK on a tile to **move** it (into and out of the dock), **hide** it, **uninstall** it or
  open **App info**. Hidden apps can be brought back from the same menu.
- If the launcher ever crashes twice in two minutes, it opens App info for the stock launcher so one
  press of Enable gets you a working home screen back.

### Install

1. Turn on developer options and USB/network debugging on the TV (Settings → Device Preferences →
   About → press OK on **Build** seven times → back → Developer options).
2. Download `vibe-launcher-v1.0.0.apk` from [Releases](https://github.com/maydings/vibe-launcher/releases/latest)
   and install it from a computer on the same network:

   ```bash
   adb connect <tv-ip>:5555
   adb install -g vibe-launcher-v1.0.0.apk      # -g grants the home-screen-cards and Wi-Fi permissions
   adb shell cmd package set-home-activity com.maydings.vibelauncher/.MainActivity
   ```

3. Press Home. To keep the stock launcher from coming back, disable it:
   `adb shell pm disable-user --user 0 com.google.android.tvlauncher` (on Google TV devices,
   `com.google.android.apps.tv.launcherx`). Undo with `pm enable`.

Installed without `-g`, the launcher asks for access to TV listings on its first start; that is what
lets it read the apps' home screen cards.

Build it yourself, configure the dock and hidden apps, and the details of each backdrop source:
[`vibe-launcher/README.md`](vibe-launcher/README.md).

---

## Debloat

Every Android TV ships with sponsored rows, store apps, telemetry and demo modes. The
[`debloat/`](debloat/) folder removes them over ADB, reversibly: packages are disabled, never
uninstalled, and a restore script puts everything back.

**Debloating requires an AI coding assistant** such as Claude Code: every brand and firmware ships
different bloat, so the assistant inspects your TV, works out what is safe to disable and asks you
about the rest. (Vibe Launcher itself needs no AI; just install the APK.)

- **[`PROMPT.md`](debloat/PROMPT.md)**: paste it into Claude Code (or any coding assistant with a
  shell) on a computer on the same Wi-Fi as the TV, fill in your model, and it backs up the TV,
  finds the bloat, asks which apps you use, disables the rest and installs Vibe Launcher, checking
  each step with screenshots from the TV.
- **[`tools/`](debloat/tools/)**: a pure-Python ADB client for when the stock `adb` is blocked
  (recent macOS), an APK uploader, and scripts to apply or undo a list.
- **[`examples/`](debloat/examples/)**: tested lists for the Sony Bravia XBR-940E and 900F, the
  NVIDIA Shield TV and the Xiaomi Mi TV 4A.

Works on Android TV and Google TV sets (Sony, TCL, Hisense, Xiaomi, Philips, the Shield and most
others). Not on Samsung Tizen, LG webOS or Fire TV. Details and the gotchas we hit:
[`debloat/README.md`](debloat/README.md).

## Tested on

| Device | Android |
|---|---|
| Sony Bravia XBR-940E | 9 |
| Sony Bravia 900F | 9 |
| NVIDIA Shield TV (2017) | 11 |
| Xiaomi Mi TV 4A | 10 |

## Credits and licence

Started as a fork of [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) and rewritten
since. MIT licence, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font
License (`vibe-launcher/FONT-LICENSE-OFL.txt`). Backdrops use the TMDB API but are not endorsed or
certified by TMDB.
