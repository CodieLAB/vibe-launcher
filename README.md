# Vibe Launcher

A clean, tvOS-inspired home screen for Android TV, and the tools to strip the ads and bloat out of
the TV it runs on. **Free and open source** (MIT), and **no ads, ever**: no accounts, no tracking, no paid tier.

| Stock Sony home screen | Vibe Launcher |
|---|---|
| ![Stock Google TV home on a Sony XBR-940E, with a sponsored carousel and a Shop tab](debloat/docs/stock-home-sony.jpg) | ![Vibe Launcher on the same TV, with a full-screen backdrop behind the dock](vibe-launcher/docs/new-home.jpg) |

The repo has two parts:

| Folder | What it is |
|---|---|
| [`vibe-launcher/`](vibe-launcher/) | The launcher. **Just download the APK from [Releases](https://github.com/maydings/vibe-launcher/releases/latest) and install it**. Source and build script included. |
| [`debloat/`](debloat/) | A script that removes the ads and bloat from the tested TVs over the network and makes Vibe Launcher the home screen, with a restore script to undo it. |

---

## Vibe Launcher

A small, fast home screen built for TVs with 1 to 2 GB of RAM. Android 8.0 and newer. Free and open
source: every feature is in the APK, nothing is behind a purchase.

![The app grid with the glass focus highlight](vibe-launcher/docs/launcher-grid.jpg)

**The home screen**

- **Dock and grid.** Your favourite apps (four, or up to six) sit in a dock at the bottom. Press Down and the rest of
  your apps slide up in a grid. A single glass highlight springs from tile to tile.
- **Backdrops.** The focused dock app fills the screen behind it with artwork from what it is
  actually offering you:
  - the cards apps publish for the TV home screen (Netflix, Apple TV, Prime Video, Jellyfin and
    others), read the same way Google's own launcher does;
  - the films and shows on those cards are shown with a clean, full-size backdrop and the title's
    own logo instead of the apps' art (no "Recently added" stickers): out of the box from Stremio's
    free metadata, or from **TMDB** with a free key;
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
- Vibe Launcher settings start with **tiles per row**: 4 (the default), 5 or 6. Tiles shrink to fit,
  and the dock holds as many apps.
- Below that, the TMDB, YouTube and Plex keys. Each has an ⓘ explaining what it does and where to get
  it. Typing keys with a remote is no fun, so the screen shows a **QR code**: scan it with your phone
  and paste the keys there. Saved keys are shown hidden but for their last four characters, on the TV
  and on the phone.
- Keys stay on the TV; the launcher never ships with any.

**Managing apps**

- Long-press OK on a tile to **move** it (into and out of the dock), **hide** it, **uninstall** it or
  open **App info**. Hidden apps can be brought back from the same menu.
- If the launcher ever crashes twice in two minutes, it opens App info for the stock launcher so one
  press of Enable gets you a working home screen back.

### Install

Vibe Launcher is just an APK: download it and install it. No account, no build tools. The
latest version is always at

**https://github.com/maydings/vibe-launcher/releases/latest/download/vibe-launcher.apk**

**On the TV, no computer needed**

1. From the Play Store, install a downloader or file manager, e.g. **Downloader** (by AFTVnews) or
   **X-plore**.
2. Open the link above in it, then open the downloaded APK. Android asks once to allow that app to
   install unknown apps; allow it.
3. Open Vibe Launcher from your apps and allow the two permissions it asks for: TV listings (the
   apps' home screen cards, for backdrops) and location (Android only reveals the Wi-Fi network's
   name with it).
4. Make it your home screen. Some TVs offer this under Settings → Apps → Default apps → Home app.
   Many Android TVs, and Google TV, have no such setting: there the Home button stays with the stock
   launcher until you run the `set-home-activity` command below once from a computer. Until then you
   can still open Vibe Launcher from your apps.

**With a computer (adb)**

On the [tested TVs](#tested-on), `debloat/debloat.sh` does all of this for you (see
[Debloat](#debloat)). By hand:

1. Turn on developer options and USB/network debugging on the TV (Settings → Device Preferences →
   About → press OK on **Build** seven times → back → Developer options).
2. Download the APK and install it from a computer on the same network, in this order:

   ```bash
   adb connect <tv-ip>:5555
   adb install -g vibe-launcher.apk      # -g grants its permissions up front
   adb shell am start -n com.maydings.vibelauncher/.MainActivity
   # Optional: keep the stock launcher from coming back (on Google TV: com.google.android.apps.tv.launcherx)
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   # Always last: Android forgets the chosen home app whenever one is enabled or disabled
   adb shell cmd package set-home-activity com.maydings.vibelauncher/.MainActivity
   ```

3. Press Home. To bring the stock launcher back, `pm enable` it. If a Google TV starts dropping off
   Wi-Fi every minute after its launcher is disabled (seen on Google TV 14, where Home falls to the
   setup wizard), run `adb shell pm enable --user 0 com.google.android.apps.tv.launcherx`.

Build it yourself, configure the dock and hidden apps, and the details of each backdrop source:
[`vibe-launcher/README.md`](vibe-launcher/README.md).

---

## Debloat

Every Android TV ships with sponsored rows, promoted apps, viewing-data tracking and demo modes. The
[`debloat/`](debloat/) folder has a script that turns them off over the network and makes Vibe
Launcher the home screen, reversibly: packages are disabled, never uninstalled, and `restore.sh`
puts back exactly what it changed.

```bash
cd debloat
./debloat.sh 192.168.1.50 --dry-run    # see what it would do
./debloat.sh 192.168.1.50              # do it
./restore.sh 192.168.1.50              # undo it
```

It needs a Mac or Linux computer with adb, and debugging turned on on the TV. It backs the TV up
first, asks before turning off anything you might use (voice search, screen mirroring, cable box
control and so on) and installs the latest Vibe Launcher. Step by step:
[`debloat/README.md`](debloat/README.md).

## Tested on

| Device | Android | Debloat list |
|---|---|---|
| Sony Bravia XBR-940E | 9 | [`sony-bravia-xbr-940e.txt`](debloat/lists/sony-bravia-xbr-940e.txt) |
| Sony Bravia 900F | 9 | [`sony-bravia-900f.txt`](debloat/lists/sony-bravia-900f.txt) |
| NVIDIA Shield TV (2017) | 11 | [`nvidia-shield-2017.txt`](debloat/lists/nvidia-shield-2017.txt) |

Vibe Launcher itself runs on any Android TV or Google TV with Android 8 or newer; the debloat script
only runs on the TVs above (and the Sony models that share their platform).

## Credits and licence

Started as a fork of [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack) and rewritten
since. MIT licence, see [`LICENSE`](LICENSE). The Outfit typeface is under the SIL Open Font
License (`vibe-launcher/FONT-LICENSE-OFL.txt`). Backdrops use the TMDB API but are not endorsed or
certified by TMDB.
