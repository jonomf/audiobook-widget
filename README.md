# Audiobook Widget

One Android home-screen widget for whatever **Libby** or **Audible** is playing,
in place of keeping both apps' widgets on the home screen.

Resize it and the layout changes to fit. The setup screen lists each widget's
real size and which layout it gets, for tuning the cutoffs in `WidgetRenderer.Kind`.
- **1×1:** cover with a play/pause button on top.
- **2×1:** cover with play/pause on it, then title and chapter (on narrow grids: cover and play/pause).
- **2×2:** big cover with play/pause.
- **3×1 / 4×1:** cover, title, chapter · author, and play/pause. Tap `LIBBY ⇄` to switch apps.
- **3×2 / 4×2 or bigger:** adds Libby / Audible chips plus skip back and skip forward.
  Skips use each app's own jump length when the app supports it, otherwise 30 seconds.
- Every cover carries a small badge with the app's own icon, so you can tell Libby from Audible.
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
- `WidgetRenderer` builds one layout per size (see `Kind`), and Android picks the one that fits.
- `ArtProvider` serves the saved covers to the launcher by URI.
- `Store` keeps the last title, author and (pre-rounded) cover for each app.
- To follow another app, add it to `Sources.kt` and to `<queries>` in the manifest.

Every push to `main` builds a new APK release (GitHub Actions).
