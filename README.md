# claude-mobile-buddy

Approve or deny Claude Code permission prompts from an Android phone, over Bluetooth Low Energy.

When `claude` in your terminal is about to ask whether it may run a command, the request appears
on your phone. You tap. The terminal unblocks. If the phone is out of range, asleep or simply
not answering, the usual terminal prompt appears after a short wait — the phone can only ever
make the decision faster, never harder to reach.

## Why a bridge

Anthropic ships a [Bluetooth API for maker hardware](https://github.com/anthropics/claude-desktop-buddy)
that drives an ESP32 desk pet. That API lives in the Claude desktop application for macOS and
Windows, under developer mode, and covers sessions hosted inside that app. The Claude Code CLI
has no Bluetooth path at all.

So this project supplies its own host: a small background agent on the Mac that receives
Claude Code hook events over localhost HTTP and relays them to the phone. The message schema
follows Anthropic's specification; the transport around it — authentication, encryption, queueing
— is ours, because both ends of this link are.

```
claude (CLI)  --HTTP-->  bridge.app (macOS)  --BLE/NUS-->  phone (Android)
                          LaunchAgent                       GATT server
                          CoreBluetooth central              + advertiser
```

## Layout

| Path | What |
|---|---|
| `android/` | Android app. Kotlin, Compose, minSdk 31. |
| `bridge/` | macOS agent. Swift, SwiftNIO, CoreBluetooth. |
| `docs/PROTOCOL.md` | Wire protocol. |
| `docs/protocol/fixtures/` | Golden vectors both implementations are tested against. |
| `backlog.md` | Deferred work and the reasoning behind deferring it. |

## Setting it up

```
cmbridge pair --png        # scan the code with the phone app
cmbridge install-hook      # merge the hooks into ~/.claude/settings.json
make app                   # build the bundle
cmbridge print-agent       # the LaunchAgent that runs it at login
```

`install-hook` reads the settings file, works out the merge, shows you the diff and asks before
writing anything, keeping the previous file as `settings.json.bak`. It appends to hook events
you already use rather than replacing them, and re-running it updates its own entry instead of
leaving a stale one behind. `print-hook` still prints the snippet if you would rather paste it
yourself.

## Security

The phone advertises itself into the air, and a GATT server that anyone in radio range can talk
to would be a remote control for approving shell commands on your workstation. So:

- Pairing transfers a 256-bit key by QR code, rendered as ASCII in your terminal and scanned by
  the phone's camera. No short code, no typing, nothing brute-forceable.
- Every frame after the handshake is AES-256-GCM with per-session, per-direction keys and a
  strictly monotonic counter. An unauthenticated connection gets no data and is dropped.
- Approving requires unlocking the phone. On the lock screen you can see that something is
  waiting, not what it wants to run.
- Both sides journal every decision, including the ones that timed out.

## Status

Early. See `backlog.md` for what is deliberately not here yet.

## License

MIT. The protocol is reimplemented from Anthropic's published specification; no code is derived
from the reference firmware.
