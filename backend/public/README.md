# Drop `app.apk` here

Put the built Android app in this folder **named exactly `app.apk`** and the backend will
serve it at `http://<server-ip>:3000/app.apk` (and on the `/install` page).

That lets cashiers install/update the app over the shop Wi-Fi/hotspot — no Google account,
no Play Store, **no internet bundles** needed on their phones.

Where to get the APK:

- Build it once: `cd android-app && gradle :app:assembleDebug`
  → `android-app/app/build/outputs/apk/debug/app-debug.apk` → copy here as `app.apk`
- Or download the `app-debug-apk` artifact from GitHub Actions and rename it.

(This file is intentionally git-ignored — don't commit binaries to the repo.)
