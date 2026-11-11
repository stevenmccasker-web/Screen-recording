# Screen Recorder

A simple, fast screen recorder for Android.

- Start / pause / stop from the app, the notification, or a **Quick Settings tile**
- 720p, 1080p or native resolution, 30 or 60 fps, three quality levels
- Optional microphone, optional 3 or 5 second countdown
- Saves to `Movies/ScreenRecorder` so videos show up in your Gallery
- Recordings list with thumbnails, play, share and delete

## Install on your phone (no Android Studio)

1. Push to this repo. GitHub Actions builds the APK automatically (Actions tab).
2. When the build is green, open the **Releases** page on your phone and download `ScreenRecorder.apk` from the "latest" release.
3. Open the file and allow "install unknown apps" if asked.

Every build is signed with the same key, so new builds install over the old one and keep your settings.

## Notes

- Needs Android 11 or newer.
- Sound from inside apps (internal audio) is not captured yet, only the microphone.
