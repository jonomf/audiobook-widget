# Audiobook Widget

One Android home-screen widget for whatever **Libby** or **Audible** is playing,
in place of keeping both apps' widgets on the home screen.

- **4×1:** cover, title, author, and play/pause. Tap `LIBBY ⇄` to switch apps.
- **4×2 or taller:** adds Libby / Audible chips plus skip back and skip forward.
  Skips use each app's own jump length when the app supports it, otherwise 30 seconds.
- Switches automatically to whichever app starts playing.
- Remembers the last book in each app, so it still shows after the app closes.
  In that case the buttons open the app so you can resume there.

## Install / update
On the phone, open the latest release and download `audiobook-widget.apk`:
https://github.com/jonomf/audiobook-widget/releases/latest

Open it and allow installs from your browser when asked. Updates install over
the old version (same signing key).

## Setup
1. Open **Audiobook** → **Allow notification access** → turn it on.
   - If the switch is greyed out ("Restricted setting"): in the app, tap **Open app info** →
     ⋮ (top right) → **Allow restricted settings**, then try again.
2. Tap **Add widget to home screen**, or long-press the home screen → Widgets → Audiobook.

Notification access is the Android permission that lets an app see and control
other apps' media sessions. This app only looks at Libby and Audible.

## How it works
- `MediaListener` is a notification-listener service. It watches Libby's and Audible's
  media sessions and redraws the widget when playback or metadata changes.
- `WidgetRenderer` builds two layouts, compact and large, and Android picks one by widget size.
- `Store` keeps the last title, author and cover for each app.
- To follow another app, add it to `Sources.kt` and to `<queries>` in the manifest.

Every push to `main` builds a new APK release (GitHub Actions).
