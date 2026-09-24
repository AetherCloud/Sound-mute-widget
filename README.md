# Sound Mute Widget

A minimal Android home screen widget that mutes media volume with a single tap. It shows the
current media volume as a progress ring around the percentage, and tapping it drops the volume
to zero with a short sweep animation. No app to open, no settings screen, no notification —
just the widget.

## Features

- **One-tap mute** — tapping the widget sets the media (`STREAM_MUSIC`) volume to zero.
- **Live volume ring** — a faint track with a bright arc up to the current volume, starting
  at 12 o'clock, with the percentage in the centre. While muted, the centre shows a muted
  glyph instead.
- **Mute sweep animation** — on tap, the filled ring rotates back toward 12 o'clock and fades
  while the empty ring fades in, and the percentage shrinks out as the muted glyph pops in.
  The animation runs on the launcher's frame loop (i.e. at the display's refresh rate), not
  stepped cross-process.
- **Tracks external changes** — the ring follows volume changes from the hardware keys or
  other apps while the widget's process is alive. It does this without a foreground service:
  a volume-changed broadcast plus a `ContentObserver` on the settings tables as a fallback.
- **Survives reboots and updates** — the widget re-renders and re-registers its listeners on
  `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- **Theme aware** — the palette follows the system light/dark theme, re-rendered when it flips.
- **No permissions beyond boot** — volume control via `AudioManager` needs no permission; the
  only declared permission is `RECEIVE_BOOT_COMPLETED`.

## How it works

1. The widget is a plain `AppWidgetProvider` rendering into two stacked `ViewFlipper` layers
   (ring and centre) so the rotation animation never spins the text.
2. The ring and centre are drawn with Canvas at the widget's density, then set as bitmaps via
   `RemoteViews` — the launcher animates the flip between the two children of each layer.
3. Tapping sends a broadcast to the provider, which calls `setStreamVolume(STREAM_MUSIC, 0)`
   and plays the sweep transition; because Android has no public volume-changed broadcast,
   live updates also hook the (hidden but stable) `AudioService` broadcast and observe the
   settings tables.

## Tech notes

- Written in Kotlin, built with Gradle (Kotlin DSL).
- Targets Android 13+ (`minSdk = 33`, `targetSdk = 37`).
- No dependencies beyond the AndroidX base libraries — the rendering is plain
  `Canvas`/`Paint`, no UI framework.
- Single source file: `app/src/main/java/dk/ftb/soundmutewidget/MuteWidget.kt`.

## AI disclosure

This app was developed with the assistance of AI (Claude, by Anthropic): the widget's
implementation, rendering and animation code were written in an AI-assisted pair-programming
session. All code was reviewed and tested by the developer, and the app is released under the
same license as the rest of this repository.
