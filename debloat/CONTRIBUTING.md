# Contributing to the debloat scripts

For the launcher itself, see [vibe-launcher/CONTRIBUTING.md](../vibe-launcher/CONTRIBUTING.md).

## Add your TV

1. Copy the closest file in `lists/` to `lists/<brand>-<model>.txt`.
2. Set its header: `# title:` with the model, Android version and firmware build, and one
   `# device:` line per `ro.product.device` value it is for (`adb shell getprop ro.product.device`).
   `debloat.sh` picks a list by that line, so only claim platforms you have tested.
3. Put each package under a section:
   - `[always] What this group is` for things nobody needs (stock launcher, promotions, telemetry,
     demo modes);
   - `[ask:yes] Question?` or `[ask:no] Question?` for anything someone might use. Say in the
     question what stops working, and pick the default (what Enter does) a typical viewer would want.
4. Try it with `./debloat.sh <tv-ip> --list lists/<your file> --dry-run`, then for real, and live
   with it for a few days. Note anything that broke in the list's header.
5. Check that `./restore.sh <tv-ip>` brings the TV back.
6. Add a row to the table in `README.md`.

Never list packages to uninstall. Everything here is disabled with `pm disable-user`, which can be
undone from the TV's own settings even without these scripts.

## Scripts

`debloat.sh`, `restore.sh` and `lib.sh` are plain bash 3.2 (what macOS ships) plus adb. Keep them
that way: no new dependencies. `tools/` holds a pure-Python ADB client for Macs where the local
network blocks adb; the scripts use it automatically.

## Pull requests

- One topic per PR. Small is fine.
- Say which TV you tested on and paste the script's output.
- Be kind in reviews. Most contributors here are doing this for their parents.

By contributing you agree your work is released under the repo's MIT licence.
