# MotionDots wire protocol (v1)

A phone streams its linear acceleration and gravity vector to the Mac over a local
WebSocket. The Mac turns that into vehicle motion and moves the overlay dots.

## Transport

- Mac app is the WebSocket **server** (Network.framework `NWListener` + `NWProtocolWebSocket`), plain `ws://`, no TLS.
- Listens on an OS-assigned port and advertises it over Bonjour as service type `_motiondots._tcp` in domain `local.`
  The Bonjour instance name is the Mac's hostname. TXT record: `v=1`.
- Phones browse for `_motiondots._tcp`, resolve it and connect to `ws://<host>:<port>/`.
- Phones also offer manual entry of `host:port` as a fallback (hotspots and some networks block mDNS).
- All frames are WebSocket **text** frames containing one UTF-8 JSON object. Every object has a string `"type"`.
- Unknown `type` values and unknown fields are ignored (forward compatibility).

## Coordinate convention (both phones MUST emit this)

Android sensor axes, in the **device frame**: `x` toward the right edge of the screen, `y` toward the top edge, `z` out of the screen toward the viewer.

- `a` = **true acceleration of the device** in m/s², gravity removed. Positive `x` means the device is accelerating toward its right edge.
  - Android: `TYPE_LINEAR_ACCELERATION` as-is.
  - iOS: CoreMotion's raw sign convention is the negative of Android's (flat face-up reads z = -1 g), so send `a = -userAcceleration * 9.80665`.
- `g` = gravity as an accelerometer at rest would read it, in m/s². It points **up**, away from the earth. Flat and face-up it is `(0, 0, +9.81)`.
  - Android: `TYPE_GRAVITY` as-is.
  - iOS: send `g = -gravity * 9.80665`.

Both iOS signs are derived from the documented conventions and need a quick on-device check: lay the phone flat, push it to the right, and `ax` should go positive. The Mac app has "invert" toggles as a safety net.

## Phone to Mac

```json
{"type":"hello","v":1,"platform":"android","name":"Pixel 9","rateHz":50}
```
Sent once, right after the socket opens. `platform` is `"android"` or `"ios"`. `rateHz` is the intended sample rate.

```json
{"type":"motion","t":12345.678,"a":[0.12,-0.40,0.03],"g":[0.1,9.7,1.2]}
```
`t` is seconds on the phone's monotonic clock (Android `SystemClock.elapsedRealtimeNanos()/1e9`, iOS `CMDeviceMotion.timestamp`). The Mac uses it only for deltas. Send 30 to 60 per second, one sample per message. Do not send faster than 60 Hz.

```json
{"type":"ping","t":12345.9}
```
Phone sends one every 2 seconds. The Mac answers with `{"type":"pong","t":<same t>}`. If the phone sees no `pong` for 6 seconds it treats the connection as dead and reconnects with backoff (1s, 2s, 4s, capped at 10s).

## Mac to phone

```json
{"type":"welcome","v":1,"host":"Jason-MacBook","rateHz":50}
```
Sent after `hello`. `rateHz` is the rate the Mac wants. The Mac only ever sends values from 20 to 60. A phone ignores any `rateHz` outside 20 to 60 and keeps its current rate.

```json
{"type":"pong","t":12345.9}
```

```json
{"type":"config","rateHz":30}
```
Optional. Sent any time to change the rate (for example 30 to save battery while the overlay is disabled).

## Versioning

`v` is `1`. A Mac that receives a `hello` with a higher `v` still accepts it and reads the fields it knows.

## Liveness

Either side treats ANY received frame as proof the link is alive, not only `pong`. A phone reconnects if it receives nothing from the Mac for 6 seconds while its pings are going out. The Mac drops a connection that sends nothing for 10 seconds.

## Mac-side limits

- A connection must send `hello` within 5 seconds or the Mac closes it.
- At most 4 phones are connected at once. At most 2 connections may be waiting for their `hello`. A new `hello` with the same `platform` and `name` as an existing phone replaces the older connection.
- `name` is truncated to 64 characters and `rateHz` in `hello` is clamped to 1 to 120. Samples faster than 120 per second from one phone are dropped.
- The Mac sends a WebSocket close frame (code 1000, or 1001 when shutting down) before dropping a connection whenever it can. Phones should do the same when they disconnect on purpose.
