# SardineSync (Android)

The Android companion app for [sardinetracker](https://github.com/alaricmoore/sardinetracker) — a local-first health tracker for systemic autoimmune rheumatic disease.

SardineSync reads your biometrics from **Health Connect** and syncs them to your own sardinetracker server. Because it uses Health Connect rather than a proprietary API, it works with whatever wearable *you* already use — Fitbit, Garmin, Samsung, Oura, Pixel Watch, or any other app that writes to Health Connect. No Apple Watch required.

> There's an iOS companion too — [sardinessync](https://github.com/alaricmoore/sardinessync) — but it's tied to Apple Health. This Android app is the one to reach for if your wearable isn't an Apple Watch.

## What it does

- Wraps your sardinetracker site in a WebView, so logging in and browsing your data feels like one app.
- Reads today's **steps, resting heart rate, HRV (RMSSD), SpO2, respiratory rate, and body temperature** from Health Connect and POSTs them to your server.
- **Backfills** up to a year of history on first run (non-destructive — the server upsert is field-level, so it never overwrites symptoms or notes you typed by hand).
- Runs a **nightly background sync** so you don't have to remember.

The sync is field-level and additive: it only fills in the biometric fields, never touching anything you've entered yourself.

## First-run setup

On first launch the app asks for three things and stores them **encrypted on the device** (Android Keystore-backed, via EncryptedSharedPreferences — the equivalent of the iOS app's Keychain use):

- **Server address** — your sardinetracker URL, e.g. `https://your-tracker.example.com`
- **Access token** — the `api_token` value from your server's `config.json`
- **Account ID** — which account on the server the readings belong to (usually `1`)

Nothing is hardcoded and no secrets live in this repo. You can change any of it later from **⋮ → Settings**.

Then grant Health Connect permissions when prompted. Two of them are *additional access* toggles Health Connect shows separately from "allow all":

- **access past data (history)** — needed for backfill to reach older than ~30 days
- **access data in the background** — needed for the nightly sync

Skipping them doesn't break anything: manual sync still works, backfill just gets ~30 days, and the nightly job waits until you grant background access.

## Installing

You don't need to build anything. Grab the latest APK from the
[**Releases page**](https://github.com/alaricmoore/sardinesync-android/releases),
open it on your phone, and allow the install when Android asks about unknown apps
(that prompt just means "not from the Play Store" — the APK is built and signed
automatically from this exact source code by the [release workflow](.github/workflows/release.yml)).

Your phone needs Health Connect: built in on Android 14+, a Play Store install on 11–13.

Want updates to arrive on their own? Install [Obtainium](https://github.com/ImranR98/Obtainium)
and add this repo's URL — it watches the Releases page and updates the app like a store would.

## Building from source

If you'd rather build it yourself, you need the Android SDK and a **JDK 17–21**.

> **Heads up:** the Android Gradle Plugin does not yet run under JDK 25. On distros that ship a very new JDK (recent Fedora, etc.), install a JDK 21 alongside it and point `JAVA_HOME` at that one for the build — the system JDK can stay where it is.

```bash
git clone https://github.com/alaricmoore/sardinesync-android.git
cd sardinesync-android
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

Sideload the APK, open it, and fill in the setup screen.

## License

GNU Affero General Public License v3.0 (AGPL-3.0), matching sardinetracker. Free for individuals and non-profits with attribution; commercial use requires a separate license.
