# Backlog

Deferred work, with the reasoning that put it here. Items are not ordered by priority.

## v2 — the pet

The upstream reference implementation (`anthropics/claude-desktop-buddy`) is a virtual pet that
feeds on approvals. v1 deliberately ships the control surface only; the pet is what makes it
pleasant, not what makes it work.

- Seven animation states: `sleep`, `idle`, `busy`, `attention`, `celebrate`, `dizzy`, `heart`.
- Species picker. Upstream ships 18 ASCII species plus GIF character packs.
- GIF rendering from local device storage. We will **not** implement the `char_begin` /
  `file` / `chunk` / `file_end` / `char_end` folder-push transfer: streaming GIFs over BLE
  exists because an ESP32 has no filesystem the user can reach. A phone does.
- Accelerometer input: shake -> `dizzy`, face-down -> nap / energy refill.
- Level-ups. Upstream celebrates every 50K tokens, which depends on token accounting below.

## Token accounting

`tokens` and `tokens_today` are part of the upstream snapshot schema but no hook event carries
them. Obtaining them means watching `transcript_path` and parsing the per-message `usage`
records out of the JSONL.

Deferred because the only consumer of the number in v1 would be a label on a dashboard, and the
transcript format carries no stability guarantee. Revisit together with the pet, where the
number actually drives behaviour.

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
  rendered at once. v1 serialises them through a FIFO queue with a `+N waiting` counter instead;
  a phone screen is a poor place for a queue of five.
- Multiple hosts connected simultaneously. The keyring already stores several hosts and the
  handshake already carries a host id, but only one host is served at a time — a second one is
  refused with a reason. Serving both breaks the single FIFO queue and makes it ambiguous whose
  `rm -rf` is on screen.

## Distribution

- Developer ID signing and notarisation of the bridge `.app` bundle, so it can be downloaded
  from Releases and opened without clearing the quarantine attribute. Requires an Apple
  Developer Program membership. v1 builds from source locally, which Gatekeeper does not
  quarantine.
- Publishing the Android app anywhere other than GitHub Releases. A foreground service holding
  a BLE advertiser is a review conversation that a personal developer tool does not need to have.

## Android

- Full-screen intent for approval prompts, i.e. the screen lighting up like an incoming call.
  Android 14 restricted `USE_FULL_SCREEN_INTENT` to calling and alarm apps; everyone else needs
  an explicit user grant through a separate settings screen. Worth the onboarding step only if
  heads-up notifications turn out to be missable in practice.
- Wear OS companion.
- iOS. CoreBluetooth can act as a peripheral, but a backgrounded iOS app drops the local name
  from its advertisement and moves service UUIDs into the overflow area, where a desktop scanner
  will not see them. This is why the phone app is native Android rather than React Native.

## Pairing

- SPAKE2 or an equivalent PAKE, so a six-digit code yields a full-entropy key the way Bluetooth
  LE Secure Connections does. v1 transfers a 256-bit key by scanning a QR code rendered as ASCII
  in the terminal, which needs no PAKE and no typing, at the cost of requiring a camera.
- Key rotation and per-host revocation from the phone.

## Bridge packaging

The bridge is currently a plain executable produced by `swift build`. A command-line binary has
no TCC identity of its own — it inherits Bluetooth permission from whatever launched it — so it
works when started from a terminal that has been granted Bluetooth, and not otherwise.

Making it survive a reboot means an `.app` bundle carrying `NSBluetoothAlwaysUsageDescription`,
marked `LSUIElement` so it stays out of the Dock, started by a LaunchAgent. Until that exists,
run it from a terminal.

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
