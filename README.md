# MotionDots for Android

The Android companion app for MotionDots. It reads the phone's linear-acceleration and gravity
sensors and streams them over a local WebSocket to the MotionDots Mac app, which draws the
anti-motion-sickness dots. The app is a single Gradle module (`:app`), Kotlin + Jetpack Compose,
with no DI framework.

The wire format is documented in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Build

Requires an Android SDK (compileSdk 37, build-tools 36.0.0) and a JDK 21 toolchain
(Gradle resolves the toolchain; `gradle/gradle-daemon-jvm.properties` pins the daemon JVM).

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build type is signed with the committed debug keystore so it also installs, but R8 is
disabled; this is a personal sideloaded app.

## Permissions (Android 13+)

On first launch grant:

- **Notifications**: streaming runs in a foreground service, which shows a persistent
  notification while active.
- **Nearby devices / local network access**: where the OS asks, allow it so the app can discover
  the Mac on your Wi-Fi network.

## Connecting to the Mac

The app finds the Mac in one of two ways:

1. **Automatic discovery** via NSD / Bonjour, looking for the `_motiondots._tcp` service that the
   Mac app advertises. Phone and Mac must be on the same Wi-Fi network (and the network must not
   block multicast or client-to-client traffic).
2. **Manual entry** of `host:port` (for example `192.168.1.20:8765`) if discovery does not work.
   The connection is a plain `ws://` WebSocket; cleartext traffic is enabled in the manifest for
   this reason.

## Battery optimization

Android may throttle sensors and networking when the screen is off or the app is in the
background. If the stream stalls, exclude MotionDots from battery optimization:
Settings > Apps > MotionDots > Battery > Unrestricted. The foreground service and a partial wake
lock keep streaming alive while you ride, but vendor battery managers can still be aggressive.

## Project layout

```
app/                     the only Gradle module
android-lint/            lint configs; select with -Plint-config=<name> (default, all)
ci/                      ktfmt and shellcheck helper scripts used by CI and the git hook
.githooks/pre-commit     shellcheck + ktfmt (enable: git config core.hooksPath android/.githooks)
gradle/libs.versions.toml  version catalog
```

## License

MIT, see [LICENSE](LICENSE).
