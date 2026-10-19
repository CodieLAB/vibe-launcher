# The prompt

Paste this into Claude Code (or another coding assistant with shell access) on a computer that is
on the same Wi-Fi as the TV. Works on Android TV / Google TV sets (Xiaomi, TCL, Hisense, Sony,
OnePlus, Realme, Nvidia Shield and most others). It will not work on Samsung Tizen, LG webOS or
Fire TV. If you cloned this repo, mention that: the assistant can reuse `debloat/tools/` instead of writing
an ADB client from scratch. The launcher is the ready-built Vibe Launcher APK from the repo's
Releases page.

Before you start, on the TV: Settings → Device Preferences → About → press OK on "Build" 7 times,
then go back → Developer options → turn on USB debugging (and Network/ADB debugging if your TV
shows it). Keep the TV on.

---

I want to debloat my Android TV and replace its launcher with Vibe Launcher, a clean Apple TV style home screen.
My TV model is: <MODEL NAME HERE>. Work on the real device, step by step, and verify everything you
do with screenshots pulled from the TV. Ask me before anything that depends on my preferences.

Connect
- Find the TV on my network (hostname, ARP table, or ask me for the IP) and connect over ADB on port 5555.
- If the stock adb binary fails with "No route to host" on macOS even though the port is open, that is
  macOS Local Network privacy blocking adb. Don't debug it: use the Python `adb-shell` package in a venv
  as the ADB client instead. If its file push hangs on larger files, transfer files as base64 chunks
  through the shell and verify the md5.
- Tell me when the "Allow USB debugging?" prompt will appear on the TV so I can accept it.

Back up first
- Save the full package list, disabled-package list, getprop output and all settings to a backup folder
  before changing anything, and write a restore script that re-enables everything you disable.

Debloat
- List installed packages and sort them into: manufacturer telemetry/analytics, manufacturer content
  and ad services (PatchWall, recommendation rows, content providers), background services I probably
  don't need (share/cast duplicates, cleaners, update services), preloaded apps, and streaming apps.
- Disable the first categories with `pm disable-user --user 0`, never uninstall system packages.
- Before touching streaming apps, voice assistant, Chromecast, Miracast or the update service, ask me
  which ones I use with a checklist question and disable only what I don't tick.
- Run `pm` commands one per shell call, chained commands may silently stop after the first.

Launcher
- Install Vibe Launcher, a free, open-source home screen with no ads:
  https://github.com/maydings/vibe-launcher/releases/latest/download/vibe-launcher.apk (or build it
  from `vibe-launcher/` if I cloned the repo). Don't install launchers that show ads or charge for basics.
- Order matters: `adb install -g` the APK (grants its TV listings and location permissions), start it
  once with `am start -n com.maydings.vibelauncher/.MainActivity`, disable the stock launcher
  (`com.google.android.tvlauncher`, or `com.google.android.apps.tv.launcherx` on Google TV) with
  `pm disable-user --user 0`, and only then set Home with
  `cmd package set-home-activity com.maydings.vibelauncher/.MainActivity`. Android forgets the chosen
  home app whenever a home app is enabled or disabled, so setting it must come last.
- Uninstall other third-party launchers (Projectivy and the like): they take Home back after a reboot.
- Ask me which apps go in the dock and in what order, and arrange them on the TV: long-press OK on a
  tile → Move. Tiles per row (4, 5 or 6) is under Settings → Vibe Launcher settings.
- If a Google TV starts dropping off Wi-Fi after its launcher is disabled (seen on Google TV 14), enable
  `com.google.android.apps.tv.launcherx` again.

Verify
- After each change, inject remote key presses over ADB, take a screenshot from the TV and look at it.
  Fix what you see before moving on.
- Send one key press at a time, then check which app is in front and what has focus before the next.
  Never chain presses: a layout change can put a different app under the same keys, and a stray OK
  inside a streaming app can land on a subscription page. Prefer `am start` to reach a screen.
- When done, give me the restore script and a short list of what was disabled and how to re-enable any
  of it.

---

Expect to answer three or four checklist questions (which apps you use, which four go in the dock,
whether to keep the voice assistant, Chromecast and firmware updates). Everything is reversible.
