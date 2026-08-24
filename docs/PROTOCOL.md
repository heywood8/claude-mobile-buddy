# Protocol

Wire protocol between the macOS **bridge** and the Android **phone app**.

The message schema is derived from the Bluetooth API that Anthropic documents for maker
hardware in [`anthropics/claude-desktop-buddy`](https://github.com/anthropics/claude-desktop-buddy)
(`REFERENCE.md`). That specification describes an unauthenticated, unencrypted link between the
Claude desktop application and an ESP32 device. This project targets the Claude Code CLI
instead, supplies its own host implementation, and therefore owns both ends of the link — so the
schema is kept, and everything around it is replaced.

This document is written from the specification, not from the firmware. No code is derived from
the reference implementation.

## Roles

| Side | BLE role | Runs |
|---|---|---|
| Phone (Android) | peripheral — GATT server + advertiser | `android/` |
| Mac (bridge) | central — GATT client | `bridge/` |

The phone advertises; the bridge scans, connects and drives the session. The bridge holds the
Claude Code hook's HTTP request open while it waits for the phone to answer.

## Transport

Nordic UART Service, retained from the reference specification because every BLE debugging tool
renders it as a console out of the box.

| Component | UUID |
|---|---|
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX — central writes to peripheral | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX — peripheral notifies central | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |

Payload is UTF-8, one JSON object per line, terminated by `\n`. A line may exceed the negotiated
ATT MTU and is then split across writes or notifications; the receiver reassembles until it sees
`\n`. Each fragment is at most `MTU - 3` bytes. The bridge requests an MTU of 517; the phone must
work correctly at the 23-byte default if negotiation fails.

Decoded lines are capped at 8 KiB. A longer line is a protocol violation and terminates the
session — the sender is responsible for truncating variable-length fields (see `hint`).

### Advertising

- Advertisement data carries the NUS service UUID.
- Scan response carries the local name. The 128-bit UUID leaves too little room in the 31-byte
  legacy advertisement for both.
- The local name is not semantically significant. The bridge selects a peripheral by service
  UUID and confirms identity through the handshake, never by name.

Advertising runs inside a foreground service of type `connectedDevice`, so it survives the app
being backgrounded.

## Pairing

Pairing establishes a 256-bit pre-shared key. It happens once per (bridge, phone) pair and does
not use BLE bonding: Android cannot expose the DisplayOnly IO capability an app-controlled
passkey would need, and link-layer encryption alone would still leave the channel open to any
central in radio range.

The bridge generates the material and renders it as a QR code drawn with ASCII in the terminal.
The phone scans it with the camera. Nothing is typed.

QR payload:

```
cmb://pair?h=<host_id>&k=<key>&n=<host_name>
```

| Field | Encoding | Meaning |
|---|---|---|
| `h` | 32 lowercase hex chars | `host_id`, 16 random bytes. Opaque; not derived from hostname. |
| `k` | base64url, unpadded | `psk`, 32 random bytes |
| `n` | percent-encoded UTF-8 | human-readable host name, shown on the phone |

The phone stores entries in a keyring and can hold several hosts. `host_id` is opaque
specifically so that the plaintext `hello` below does not leak a machine name to a sniffer.

## Session

### 1. Connect

The bridge connects and subscribes to TX notifications.

### 2. Handshake — plaintext

Two lines, in the clear, carrying no secrets:

```json
{"t":"hello","v":1,"host":"<host_id>","hs":"<base64 host_salt>"}
{"t":"challenge","v":1,"ps":"<base64 phone_salt>"}
```

`host_salt` and `phone_salt` are 32 random bytes each, fresh per session.

The phone answers `hello` with `challenge` only if `host_id` is in its keyring and no other host
is currently active. Otherwise it sends `bye` and disconnects:

```json
{"t":"bye","reason":"unknown_host"}
{"t":"bye","reason":"busy"}
{"t":"bye","reason":"version"}
```

Serving one host at a time is deliberate: a shared FIFO queue across hosts makes it ambiguous on
screen whose command is being approved.

### 3. Key derivation

```
K_session = HKDF-SHA256(ikm = psk,       salt = host_salt || phone_salt, info = "cmb/v1/session", L = 32)
K_h2p     = HKDF-SHA256(ikm = K_session, salt = "cmb/v1",                info = "h2p",            L = 32)
K_p2h     = HKDF-SHA256(ikm = K_session, salt = "cmb/v1",                info = "p2h",            L = 32)
```

Salts are non-empty throughout. RFC 5869 lets an absent salt stand for a string of HashLen
zeros, and two implementations can quietly disagree about whether "empty" means "absent" — which
would produce different keys and a link that connects and then says nothing.

Session keys are derived per connection so that the AES-GCM counter can restart at zero without
ever reusing a nonce under a long-lived key. Separate directional keys mean a frame cannot be
reflected back at its sender.

### 4. Encrypted frames

Every line after the handshake is:

```json
{"n":<counter>,"c":"<base64 ciphertext||tag>"}
```

The plaintext inside a frame is the JSON object alone, with **no trailing newline**. The newline
is framing for the outer stream, and the envelope above is already a line of its own; sealing
one as well would encrypt a byte that means nothing to the reader — and would put the two
implementations one byte apart, which is exactly the kind of disagreement the shared vectors
exist to catch.

- `counter` — unsigned, starts at 0, increments by exactly 1 per frame, per direction.
- Nonce — 12 bytes: eight zero bytes followed by `counter` big-endian.
- AAD — the ASCII decimal representation of `counter`.
- Cipher — AES-256-GCM, 16-byte tag.

A receiver drops the session on any of: authentication failure, a counter that is not exactly
one greater than the last accepted one, or a malformed frame. Counters are explicit rather than
implicit so that a dropped notification is detected instead of silently desynchronising the
stream.

### 5. Mutual confirmation

The first encrypted frame in each direction doubles as proof of key possession.

Bridge, counter 0:

```json
{"t":"auth","name":"<host_name>","time":[<unix_seconds>,<utc_offset_seconds>]}
```

Phone, counter 0:

```json
{"t":"ready","device":"<model>","proto":1}
```

Either side failing to decrypt the other's first frame terminates the session. No plaintext path
exists past the handshake.

## Messages

### Snapshot — bridge to phone

Full state, not a delta. Sent on any change and as a keepalive every 10 seconds. The phone
treats the link as dead after 30 seconds of silence.

```json
{
  "t": "snap",
  "total": 3,
  "running": 1,
  "waiting": 2,
  "msg": "approve: Bash",
  "entries": ["10:42 git push", "10:41 bun test", "10:39 edit App.js"],
  "prompt": {
    "id": "req_abc123",
    "tool": "Bash",
    "hint": "rm -rf /tmp/foo",
    "cwd": "~/git/github/claude-mobile-buddy",
    "expires": 1775731279
  }
}
```

| Field | Source |
|---|---|
| `total`, `running` | `SessionStart` / `SessionEnd` hooks |
| `waiting` | depth of the bridge's approval queue |
| `msg` | short status line |
| `entries` | recent tool calls, from `PostToolUse`, host-formatted with the host's clock |
| `prompt` | head of the approval queue, absent when the queue is empty |

`prompt.expires` is an extension over the reference schema: it is the wall-clock second at which
the bridge will give up and fail open, so the phone can render a countdown rather than a
spinner. `cwd` is likewise an extension — with several sessions in flight, the tool name alone
does not say which project is asking.

`hint` is truncated by the bridge to 512 bytes.

### Decision — phone to bridge

```json
{"cmd":"permission","id":"req_abc123","decision":"once"}
{"cmd":"permission","id":"req_abc123","decision":"deny"}
```

A decision whose `id` is not the current head of the queue is ignored — it raced with a timeout.

Mapping onto the Claude Code hook response:

| `decision` | `PermissionRequest` response |
|---|---|
| `once` | `{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}` |
| `deny` | `{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"Denied from phone"}}}` |
| no answer within the window | `{}` — no decision, Claude Code falls back to the terminal dialog |

### Termination

```json
{"t":"bye","reason":"<reason>"}
```

Sent by either side before disconnecting. Reasons: `unknown_host`, `busy`, `version`,
`bad_frame`, `bad_counter`, `shutdown`.

## Queue and timing

- One approval is on screen at a time. Others queue behind it; `waiting` carries the depth.
- Each request's 45-second window starts when the bridge receives it, not when it reaches the
  head of the queue. Total latency is therefore bounded regardless of queue depth.
- On expiry the bridge answers the hook with `{}` and drops the request from the queue. The
  phone learns this from the next snapshot.
- With no phone connected the bridge answers `{}` immediately rather than waiting.

## Test vectors

`docs/protocol/fixtures/` holds golden vectors — handshake lines, derived keys, encrypted frames
with fixed salts and counters, snapshots and decisions. The Kotlin and Swift codecs are both
unit-tested against these same files, so the two implementations cannot drift apart without CI
noticing.

Fixed inputs are used deliberately: the vectors are test data, never live key material.
