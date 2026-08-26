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
make install               # build the bundle and copy it to ~/Applications
cmbridge print-agent       # the LaunchAgent that runs it at login
```

`install-hook` reads the settings file, works out the merge, shows you the diff and asks before
writing anything, keeping the previous file as `settings.json.bak`. It appends to hook events
you already use rather than replacing them, and re-running it updates its own entry instead of
leaving a stale one behind. `print-hook` still prints the snippet if you would rather paste it
yourself.

`make install` puts the bundle in `~/Applications` and stops; loading the agent is left to you.
The copy matters because launchd stores the path it is handed and never looks again — an agent
pointed into the build directory works until the first `make clean` or the first time the
checkout moves, and then fails silently, respawning a file that is no longer there.

## What reaches the phone

Only the requests Claude Code would have put in front of you anyway. The `PermissionRequest`
hook fires when it is about to ask; anything already covered by your `permissions.allow` never
gets that far, and neither does anything at all while the session runs in a mode that does not
ask — `auto`, `acceptEdits`, `bypassPermissions`. A quiet phone in those modes is the system
working, not failing.

The `PostToolUse` entry approves nothing. It fires after the fact and feeds the recent-calls
list on the dashboard, and the whole thing works without it.

To keep working in `auto` and still have particular things reach you, name them in
`permissions.ask` in `~/.claude/settings.json` — `ask` outranks `allow`, so a rule you set at
user scope holds whatever an individual project's settings say. `bypassPermissions` is the
exception: it ignores the permission rules entirely, so nothing is ever asked and nothing ever
reaches the phone.

Claude Code reads its configuration once, when a session starts. A session already running
keeps the hooks and permission rules it started with, so `install-hook` and any edit to
`permissions.ask` take effect in the *next* session, not the one you are sitting in. A phone
that stays quiet right after either is the expected outcome, and restarting the session is the
whole fix.

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
- A bridge can be taken away. **Manage** on the dashboard lists what is paired and forgets one
  after asking; forgetting drops the live link on the spot rather than waiting for it to end,
  because the session keys were derived at handshake time and outlive the keyring entry they
  came from. Rotating a key is the same gesture from the other end: `cmbridge pair --rotate`
  makes a new one, and scanning it replaces the entry instead of adding a second.

## Status

Early. See `backlog.md` for what is deliberately not here yet.

## License

MIT. The protocol is reimplemented from Anthropic's published specification; no code is derived
from the reference firmware.
