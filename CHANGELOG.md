# Changelog

## 1.2 (2026-09-24)

- **Never mutes headphones.** With Bluetooth or wired headphones connected, the
  tap is a deliberate no-op: `setStreamVolume` targets whichever output is
  active, so a mute would zero the headphones' volume — and on
  absolute-volume headsets, their own hardware level. The speaker's stored
  volume is never touched either way.
- **The ring shows where the audio is going.** While headphones are connected,
  the ring and percentage tint blue for Bluetooth and brass for wired, in
  theme-matched shades. The tint appears and disappears the moment a device
  connects or disconnects.

## 1.1 (2026-09-24)

- Keep the widget fresh after OEMs kill its process (a re-render heartbeat that
  also re-registers the volume listeners).
- Speed up the mute sweep by 40%.
- Fix the flash on every volume re-render.

## 1.0 (2026-09-24)

- Initial release: one-tap media mute widget with a live volume ring, mute
  sweep animation, external-change tracking, and a theme-aware palette.
