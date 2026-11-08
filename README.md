# Audiobook Widget

One Android home-screen widget for whatever **Libby** or **Audible** is playing,
in place of keeping both apps' widgets on the home screen.

Resize it and the layout changes to fit. The setup screen lists each widget's
real size and which layout it gets, for tuning the cutoffs in `WidgetRenderer.Kind`.
- **1×1:** cover with a play/pause button on it, app icon beneath.
- **2×1:** cover with play/pause on it, then [app icon] title, chapter, author.
- **2×2:** big cover with play/pause.
- **3×1 / 4×1:** cover, [app icon] title, chapter, author, play/pause.
- **3×2 / 4×2 or bigger:** bigger cover and text (2-line title and chapter), plus an app switcher and skip back/forward.
  Skips use each app's own jump length when the app supports it, otherwise 30 seconds.
- The app's own icon sits beside the title (tap it to switch apps), never on the cover.
- Switches automatically to whichever app starts playing.
- Remembers the last book in each app, so it still shows after the app closes.
  In that case the buttons open the app so you can resume there.

## Install / update
On the phone, open the latest release and download `audiobook-widget.apk`:
https://github.com/jonomf/audiobook-widget/releases/latest

Open it and allow installs from your browser when asked. Updates install over
the old version (same signing key).

## Updating
From build 6 on, the app updates itself:
- A small **download dot** appears in the widget's top corner when a newer build is on GitHub.
  Tap it to download and install. The app checks every few hours, and whenever you open it.
- Or long-press the widget → its **settings** entry (pencil) → **Update now**.

One-time setup, in the app under **Updates**: paste a fine-grained GitHub token with
*Contents: Read-only* on this repo (the app links to the token page). The first update also asks
you to allow this app to install apps. Later updates may skip the confirm screen entirely.

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

- `Updater` + `InstallReceiver` handle self-updates. They're self-contained: copy them into
  another app, change `REPO` / `ASSET`, and add the manifest entries (INTERNET,
  REQUEST_INSTALL_PACKAGES, the receiver).

Every push to `main` builds a new APK release tagged `build-N` (GitHub Actions); N is the versionCode.
