# Debloat

Take the ads, sponsored rows, promoted apps, viewing-data tracking and demo modes off your TV, and make
[Vibe Launcher](../vibe-launcher/) its home screen. One script, run from a Mac or Linux computer on
the same network as the TV. Everything is reversible: packages are disabled, never uninstalled, and
`restore.sh` puts back exactly what was changed.

![The stock Sony home screen this replaces: a sponsored carousel, a Shop tab and app promotions](docs/stock-home-sony.jpg)

## Tested TVs

| TV | Android | List |
|---|---|---|
| Sony Bravia XBR-940E | 9 | [`lists/sony-bravia-xbr-940e.txt`](lists/sony-bravia-xbr-940e.txt) |
| Sony Bravia 900F | 9 | [`lists/sony-bravia-900f.txt`](lists/sony-bravia-900f.txt) |
| NVIDIA Shield TV (2017) | 11 | [`lists/nvidia-shield-2017.txt`](lists/nvidia-shield-2017.txt) |

The script reads the TV's platform and picks the list itself. Other Sony TVs of the same years share
these platforms (2017-2018: `BRAVIA_ATV2`, 2018-2019 4K: `BRAVIA_ATV3_4K`) and are matched too, but
only these three models were tested. Any other TV is refused rather than guessed at.

## Run it

1. **On the TV**, turn on debugging: Settings → Device Preferences → About → press OK on **Build**
   seven times → back → Developer options → **USB debugging** on (and Network/ADB debugging if your
   TV shows it). Note the TV's IP address (Settings → Network).
2. **On the computer**, install adb if you don't have it (`brew install android-platform-tools` on
   macOS, `sudo apt install adb` on Debian/Ubuntu), then download this repo and open a terminal in
   this folder.
3. Try it without changing anything:

   ```bash
   ./debloat.sh 192.168.1.50 --dry-run
   ```

   The first time, the TV asks **"Allow USB debugging?"**: tick "Always allow" and accept.
4. Run it for real:

   ```bash
   ./debloat.sh 192.168.1.50
   ```

It then:

1. backs up the TV's package list, settings and current home app to `backups/` on your computer;
2. disables the groups that are always bloat (stock launcher, promotions, tracking, demo modes, help
   apps) and asks about the rest one group at a time: voice search, screen mirroring and DLNA,
   cable box IR control, TalkBack and so on. Each question says what stops working, and pressing
   Enter takes the tested answer;
3. downloads the latest Vibe Launcher release, installs it with its permissions granted and opens it;
4. disables what you chose, then sets Vibe Launcher as the home screen last (Android forgets the
   chosen home app whenever a home app is enabled or disabled);
5. offers to uninstall Projectivy Launcher if it's there: it takes the Home button back after a
   reboot.

Running it again is safe: anything already disabled is skipped. `--defaults` answers every question
with its default without asking, and `--help` lists the other options.

## Undo

```bash
./restore.sh 192.168.1.50
```

Re-enables exactly what `debloat.sh` disabled on that TV (from its newest backup; anything the
factory had already turned off stays off), gives the Home button back to the stock launcher and asks
whether to uninstall Vibe Launcher too. `--dry-run` works here as well.

Without a backup, `./restore.sh 192.168.1.50 --list lists/<file>.txt` re-enables a whole list. Every
package can also be turned back on from the TV itself: Settings → Apps, find it (including system
apps) and choose Enable.

## What's kept

Each list starts with what it deliberately leaves alone. On all three: Chromecast, system updates and
the screensaver. On the Sonys also Live TV (the tuner and HDMI inputs) and Sony's picture and sound
settings; on the Shield, voice search, GeForce NOW and NVIDIA Games, and network storage. Streaming
apps are never touched: uninstall the ones you don't want from the Play Store.

## If something goes wrong

- **macOS: adb says "No route to host"** although the TV is on. Recent macOS can block adb on the
  local network. The script then switches to its own Python ADB client in [`tools/`](tools/) (it sets
  itself up on first use and needs Python 3).
- **No "wireless debugging" toggle**: on these TVs USB debugging alone opens network ADB on port 5555.
- **The TV keeps asking "Allow USB debugging?"**: tick "Always allow" before accepting.
- **Home goes back to the old launcher after a reboot**: another launcher (often Projectivy) is
  installed. Uninstall it and run the script again.
- **A different Vibe Launcher is installed** (for example one you built yourself): the script offers
  to replace it with the release, which loses its keys and tile layout, or to keep it.

## Files

| File | What it is |
|---|---|
| [`debloat.sh`](debloat.sh) | Backs up, disables, installs Vibe Launcher and sets it as home. |
| [`restore.sh`](restore.sh) | Undoes it from the backup. |
| [`lists/`](lists/) | One tested list per TV: groups of packages, each always disabled or asked about. |
| [`lib.sh`](lib.sh) | Shared by both scripts: connecting, reading lists, asking. |
| [`tools/`](tools/) | Python ADB client used when adb itself can't connect. |

To add your TV, see [CONTRIBUTING.md](CONTRIBUTING.md).
