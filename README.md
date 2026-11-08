# Audiobook Widget

One Android home-screen widget for whatever **Libby** or **Audible** is playing,
in place of keeping both apps' widgets on the home screen.

It uses all the space you give it. Resize it and the layout changes:
- **1×1:** the cover, as big as it gets, with play/pause on its corner.
- **2×1:** cover with play/pause, then [app icon] title, chapter, live "time left", author.
- **3×1:** in thirds: cover with play/pause | the same text | your previous book (app icon + cover; tap to switch to it).
- **4×1:** the same plus covers of your other recent books (tap one to switch to it).
- **2×2:** big cover, title, chapter.
- **3×2 / 4×2:** big cover, all text, and every control: chapter back/next, skip back/forward,
  play/pause, playback speed (when the app allows it).
- **2×3+:** cover, text, controls, and a list of recent books.
- **4×3+:** everything: big cover, all text, every control, Libby/Audible switcher, recent books.

On every smaller size, **tap the cover or text to open the full panel**: the 4×3 view, floating
over your home screen. Tap outside it to close.

- The app's own icon sits beside the title; tap it to switch apps.
- Switches automatically to whichever app starts playing.
- Remembers the last book in each app, so it still shows after the app closes.
  In that case the buttons open the app so you can resume there.

## Install / update
On the phone, open the latest release and download `audiobook-widget.apk`:
https://github.com/jonomf/audiobook-widget/releases/latest

Open it and allow installs from your browser when asked. Updates install over
the old version (same signing key).

## Updating
Long-press the widget → its settings entry (pencil), or open the app → **Get the latest build**,
download the APK and open it. It installs over the old version.

This app deliberately has **no internet access**. An in-app updater (build 6) combined
notification access with internet + app-install permissions, which Play Protect blocks outright
as a fraud/dropper pattern for sideloaded apps. Updates have to come from a separate app.

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
- `WidgetRenderer` builds one layout per size (see `Kind`: what each contains), and Android picks the one that fits.
- `PanelActivity` is the expand panel; it draws the XL layout with the same code.
- `ArtProvider` serves the saved covers to the launcher by URI.
- `Store` keeps recent books across all apps (title, chapter, author, pre-rounded cover).
- `RecentFetcher` asks apps that aren't running for their last-played book (media browser "recent" root), best effort.
- To follow another app, add it to `Sources.kt` and to `<queries>` in the manifest.


Every push to `main` builds a new APK release tagged `build-N` (GitHub Actions); N is the versionCode.
