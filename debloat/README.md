# Debloat

Take the ads, sponsored rows, store apps, telemetry and demo modes off an Android TV over ADB, and
put [Vibe Launcher](../vibe-launcher/) on it. Everything is reversible: packages are disabled for the
user, never uninstalled, and `tools/restore.sh` puts them and the stock launcher back.

![The stock Sony home screen this replaces: a sponsored carousel, a Shop tab and app promotions](docs/stock-home-sony.jpg)

## With a coding assistant (easiest)

1. On the TV: Settings → Device Preferences → About → press OK on **Build** seven times → back →
   Developer options → turn on **USB debugging** (and Network/ADB debugging if your TV shows it).
   Keep the TV on and on the same Wi-Fi as your computer.
2. Open Claude Code (or another assistant with a shell) on the computer and paste the prompt from
   [`PROMPT.md`](PROMPT.md), filling in your TV model. Mention that you cloned this repo so it reuses
   `tools/` and the launcher.
3. Accept the "Allow USB debugging?" prompt on the TV when told to, then answer its questions: which
   apps you use and which four go in the dock.

The assistant backs up the TV's package list and settings, finds the bloat for your brand, disables
it, installs the launcher, and checks every step with screenshots taken from the TV.

## By hand

Run these from this folder. `tools/tv.py` is a pure-Python ADB client, for when the stock `adb` is
blocked (see the gotchas); with a working `adb` you can use that instead.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # your TV's IP
tools/venv/bin/python -I tools/tv.py --probe                 # model, Android version, firmware
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # one command
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/disable.sh examples/sony-bravia-xbr-940e/debloat.txt   # apply a list
tools/restore.sh examples/sony-bravia-xbr-940e/debloat.txt   # undo it, restore the stock launcher
```

Install the launcher **before** disabling the stock one, or Home lands on a factory test app. Lists
are plain text, one package per line, `#` for comments: comment out anything you use.

| File | What it is |
|---|---|
| [`PROMPT.md`](PROMPT.md) | The prompt. Brand-neutral; the assistant discovers your TV's bloat. |
| [`tools/tv.py`](tools/tv.py) | ADB client: shell, batch, push, pull, probe. |
| [`tools/b64push.py`](tools/b64push.py) | Uploads larger files (APKs) as base64 chunks with an md5 check. |
| [`tools/disable.sh`](tools/disable.sh), [`tools/restore.sh`](tools/restore.sh) | Apply or undo a list. |
| [`examples/`](examples/) | Tested lists per TV. |

## Tested lists

| Device | Android | List |
|---|---|---|
| Sony Bravia XBR-940E | 9 | [`examples/sony-bravia-xbr-940e`](examples/sony-bravia-xbr-940e/debloat.txt) |
| Sony Bravia 900F | 9 | [`examples/sony-bravia-900f`](examples/sony-bravia-900f/debloat.txt) |
| NVIDIA Shield TV (2017) | 11 | [`examples/nvidia-shield-2017`](examples/nvidia-shield-2017/debloat.txt) |
| Xiaomi Mi TV 4A | 10 | [`examples/xiaomi-mi-tv-4a`](examples/xiaomi-mi-tv-4a/debloat.txt) |

Works on Android TV and Google TV sets in general (Sony, TCL, Hisense, Xiaomi, Philips, the Shield and
most others). Not on Samsung Tizen, LG webOS or Amazon Fire TV: different systems, no ADB launcher
replacement.

## Gotchas we hit

- **macOS: `adb connect` says "No route to host"** although the port is open. Recent macOS treats the
  Google-signed `adb` as its own app under Local Network privacy and silently blocks it. `tools/tv.py`
  avoids the binary entirely.
- **No "wireless debugging" toggle** on some TVs (Xiaomi among them). USB debugging alone opens
  network ADB on port 5555.
- **Chained `pm` commands** (`pm disable a; pm disable b`) may silently stop after the first. Run one
  per shell call; `--batch` does this.
- **Large pushes hang** through the Python client. Use `tools/b64push.py` for APKs.
- **Projectivy and other launchers take Home back** after a reboot. Uninstall them once Vibe Launcher
  is set as home.
- **No on-screen keyboard** after switching launchers (seen on Xiaomi): the default input method
  pointed at a Baidu IME that isn't installed. Fix with
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`.
- **Keep what you use.** Voice search, Chromecast, system updates and the TV's own picture settings
  each live in packages that look like bloat. The example lists say which line is which.

To add your TV's list, see [CONTRIBUTING.md](CONTRIBUTING.md).
