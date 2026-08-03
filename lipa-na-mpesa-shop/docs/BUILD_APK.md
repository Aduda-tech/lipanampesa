# Building the APK

The Android project lives in `android-app/`. You have three ways to get an `.apk`:

## Option A — GitHub Actions (easiest, no installs)

The repo includes `.github/workflows/android-apk.yml`. Every push to `main` builds the
APK on GitHub's servers:

1. Push this repo to GitHub (see README “Push to GitHub”).
2. In your repo on GitHub → **Actions** tab → the green run “Android APK”.
3. Scroll to **Artifacts** → download `app-debug-apk`.
4. Unzip → `app-debug.apk` → copy to your phone → install
   (allow “Install unknown apps” when Android asks).

## Option B — Android Studio (recommended for development)

1. Install **Android Studio** (https://developer.android.com/studio).
2. **File → Open** → select the `android-app/` folder.
3. Let Gradle sync (it downloads dependencies the first time — needs internet).
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
5. The APK is at `android-app/app/build/outputs/apk/debug/app-debug.apk`.

Android Studio will offer to generate the Gradle wrapper for you — accept it,
afterwards you can also build from the terminal with `./gradlew assembleDebug`.

## Option C — Command line (needs JDK 17 + Gradle 8.5+, Android SDK installed)

```bash
cd android-app
gradle :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Release APK (for distribution / Play Store)

1. Generate a keystore:
   `keytool -genkey -v -keystore shop.keystore -alias shop -keyalg RSA -keysize 2048 -validity 10000`
2. Configure signing in `app/build.gradle` or use
   **Build → Generate Signed App Bundle / APK** in Android Studio and follow the wizard.
3. Never commit the keystore or its passwords to the repo.

## Pointing the installed app at your backend

On the login screen there is a **Backend server URL** field:

| Where the phone is | URL to use |
|---|---|
| Android emulator, backend on same PC | `http://10.0.2.2:3000/` (default) |
| Real phone + backend on PC, same Wi-Fi | `http://<PC-LAN-IP>:3000/` e.g. `http://192.168.1.50:3000/` |
| Hosted backend | `https://your-domain/` |

Find your PC's LAN IP with `ipconfig` (Windows) or `ip addr` (Linux/Mac).
