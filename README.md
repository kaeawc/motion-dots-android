# MotionDots for Android

The Android companion app for MotionDots. It reads the phone's linear-acceleration and gravity
sensors and streams them over a local WebSocket to the MotionDots Mac app, which draws the
anti-motion-sickness dots. The app is a single Gradle module (`:app`), Kotlin + Jetpack Compose,
with no DI framework.

The wire format is documented in [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

## Build

Requires an Android SDK (compileSdk 37; AGP picks its default build-tools) and a JDK 21 toolchain
(Gradle resolves the toolchain; `gradle/gradle-daemon-jvm.properties` pins the daemon JVM).
The build uses Gradle 9.8.1, AGP 9.4.0 and Kotlin 2.4.21 (language version 2.4); formatting uses
ktfmt 0.65 (`ci/install_ktfmt.sh`, `ci/apply_ktfmt.sh`). Kotlin 2.4.21 is only documented as fully
tested up to Gradle 9.7.0 / AGP 9.3.1, so KGP compatibility warnings are possible; the
conservative fallback is Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.21.

`targetSdk` is intentionally 36, not 37: at targetSdk 37 Android 17 requires the
`ACCESS_LOCAL_NETWORK` runtime permission for the app's LAN WebSocket and NSD discovery. Do not
raise it without adding that permission flow.

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

## Connection behavior

- **Handshake deadline**: if the Mac accepts the TCP connection but does not finish the WebSocket
  upgrade within 8 seconds, the attempt is dropped and retried.
- **Liveness**: any frame from the Mac counts as proof of life. If nothing arrives for 6 seconds
  while pings are going out, the app reconnects. See the Liveness section of
  [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
- **Backoff**: retries wait about 1, 2, 4, 8, then 10 seconds, each with up to 20% jitter. The
  backoff resets only after a connection was welcomed and stayed open for 10 seconds, or after its
  first pong, so a Mac that accepts and immediately drops does not cause a tight retry loop.
- **Retry budget**: if the app has not been connected for 5 minutes (counted from the last time
  it was connected, or from pressing Start), the service stops itself and the app shows "Couldn't
  reach your Mac for 5 minutes". Tap Start to try again.
- **Dropped samples**: motion samples are dropped (newest first) when more than about 400 bytes
  are still waiting to be written to the socket. The "Dropped" counter on the main screen counts
  them.

## Battery

The accelerometer sensors and the partial wake lock are held only while the phone is connected to
the Mac. While the Mac is unreachable (reconnecting) both are released, so a Mac that went to
sleep or left the network does not drain the battery, and everything is re-acquired as soon as the
connection is back. Bonjour discovery is not left running during back-off: it runs once for a
short window at the second reconnect attempt, only to follow a Mac that changed its address by
name.

On Android 15 and later the system limits how long a data-sync foreground service may run per
day. When the limit is reached streaming stops with a message; open the app and tap Start again.

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
