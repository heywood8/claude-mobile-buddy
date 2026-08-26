# Backlog

Deferred work, with the reasoning that put it here. Items are not ordered by priority.

## v2 — the pet

The upstream reference implementation (`anthropics/claude-desktop-buddy`) is a virtual pet that
feeds on approvals. v1 deliberately ships the control surface only; the pet is what makes it
pleasant, not what makes it work.

- `heart`, the one upstream state not built. The other seven are, plus `finished` and
  `resting`, which upstream has no equivalent of because an ESP32 never knew whether you had
  read the answer either.
- Species picker. Upstream ships 18 ASCII species plus GIF character packs. Worth having when
  there is a second species worth picking; the frame tables are already data, so adding one is
  a text edit.
- GIF rendering from local device storage. We will **not** implement the `char_begin` /
  `file` / `chunk` / `file_end` / `char_end` folder-push transfer: streaming GIFs over BLE
  exists because an ESP32 has no filesystem the user can reach. A phone does.
- Accelerometer input: shake -> `dizzy`, face-down -> nap / energy refill.
- Level-ups. Upstream celebrates every 50K tokens; the count that needs exists now.

## Claude Desktop app compatibility

Dropped, not merely deferred — recorded here so the decision is not silently rediscovered.

The BLE bridge is a feature of the Claude desktop application for macOS and Windows under
developer mode, covering sessions hosted inside that app. The terminal CLI has no BLE path,
which is why this project supplies its own host bridge.

Supporting both hosts would have required:

- Advertising a device name prefixed with `Claude`, which on Android is only reachable through
  `BluetoothAdapter.setName()` — a system-wide rename affecting every Bluetooth peer.
- A plaintext, unauthenticated connection path alongside the encrypted one, since the desktop
  app cannot know our key. That path would have been read-only, adding a second set of state
  transitions and a second set of edge cases for a host nobody here uses.

Nordic UART Service UUIDs are kept anyway, so the door stays open at zero cost.

## Protocol extensions

- `prompts[]` array replacing the single `prompt` field, so several pending approvals can be
  rendered at once. This was refused on the grounds that a phone screen is a poor place for a
  queue of five — which was true while the screen had one card on it. It no longer is: each
  session now has a row of its own, and a request is drawn as that session's crab asking. Two
  sessions asking at once is two crabs asking, which is the layout the app already has.
- Multiple hosts connected simultaneously. The keyring already stores several hosts and the
  handshake already carries a host id, but only one host is served at a time — a second one is
  refused with a reason. Serving both breaks the single FIFO queue and makes it ambiguous whose
  `rm -rf` is on screen.

## Distribution

- Developer ID signing and notarisation of the bridge `.app`: **dropped**. It buys a download
  that opens without clearing the quarantine attribute, and nothing else — built from source
  it is not quarantined at all. Worth revisiting only if someone other than the author wants
  the bundle.
- Publishing the Android app anywhere other than GitHub Releases. A foreground service holding
  a BLE advertiser is a review conversation that a personal developer tool does not need to have.

## Android

- Wear OS companion.
- iOS. CoreBluetooth can act as a peripheral, but a backgrounded iOS app drops the local name
  from its advertisement and moves service UUIDs into the overflow area, where a desktop scanner
  will not see them. This is why the phone app is native Android rather than React Native.

## Pairing

- SPAKE2 or an equivalent PAKE, so a six-digit code yields a full-entropy key the way Bluetooth
  LE Secure Connections does. v1 transfers a 256-bit key by scanning a QR code rendered as ASCII
  in the terminal, which needs no PAKE and no typing, at the cost of requiring a camera.

## Bridge packaging

`make app` now produces the `.app` bundle, and `cmbridge print-agent` prints the LaunchAgent
that runs it at login. What remains is the signature.

The bundle is signed ad-hoc, because a TCC grant has to attach to *some* signature. The theory
says an ad-hoc identity is the code hash, so every rebuild should invalidate the Bluetooth
permission — measured over a dozen rebuilds, a rename and a move to `~/Applications`, it never
did once. Whatever macOS is keying on here, it is not the hash. Left as written down rather
than believed.

`make install` copies the bundle to `~/Applications`, because launchd stores the path it is
handed and never looks again: an agent pointed into the build directory dies at the first
`make clean` and dies quietly.

## Swift 6 language mode

The bridge is pinned to Swift 5 language mode. In Swift 6 mode the region-based isolation
checker fails on the pattern in `HookServer` that hands a channel to a `Task` — with the
compiler's own "please file a bug" diagnostic, not a fixable diagnostic about our code.

Revisit on a later toolchain. Unrelated but worth doing at the same time: `BLELink` captures
`self` in a `@Sendable` closure and would need an isolation story of its own.

## Toolchain

`compileSdk` is pinned to 37 minor 1. Android now ships minor-versioned platforms, so the SDK
package is `platforms;android-37.1` — `platforms;android-37` does not resolve, which is worth
remembering the next time a build asks for a newer compileSdk.

Separately: the SwiftPM shipped in Command Line Tools 26.6.0 is broken — its
`libPackageDescription.dylib` exports no `Package` initialisers at all, so no manifest can link.
The toolchain in use comes from swiftly instead. CI needs a runner with Swift 6.2 or newer,
since the manifest uses `swiftLanguageMode`.

## Testing and CI

- Instrumented tests on a physical device. Neither GitHub runners nor the Android emulator
  provide a BLE peripheral role, so CI can only cover unit tests, the protocol codec against the
  shared golden vectors, and the fact that both sides compile. Everything live is verified by
  hand on the Pixel.
- Coverage badges, as in the scaffold project.
- Maestro or equivalent UI flows.
