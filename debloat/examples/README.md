# Examples

One folder per TV model, each with a `debloat.txt` listing the packages that are safe to disable
on that firmware, grouped and commented. These are starting points: comment out anything you use.

| Folder | TV | OS | Notes |
|---|---|---|---|
| `sony-bravia-xbr-940e/` | Sony Bravia XBR-940E | Android TV 9 | Sony Select, Discover, telemetry, demo modes, DLNA, Control4, IR blaster |
| `sony-bravia-900f/` | Sony Bravia 900F | Android TV 9 | Same platform as the 940E |
| `nvidia-shield-2017/` | NVIDIA Shield TV (2017) | Android TV 11 | Launcher swap, NVIDIA telemetry, extra keyboards |
| `xiaomi-mi-tv-4a/` | Xiaomi Mi TV 4A (MiTV-MOOQ1) | Android TV 10 | PatchWall, Mi analytics, SmartShare, MiLink |

To add your TV, see [CONTRIBUTING.md](../CONTRIBUTING.md). The quickest way to get a list is to
run the prompt in [PROMPT.md](../PROMPT.md) and copy the packages your assistant disabled.
