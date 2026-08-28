# Backlog

Deferred work, with the reasoning that put it here. Items are not ordered by priority.

## v2 — the pet

The upstream reference implementation (`anthropics/claude-desktop-buddy`) is a virtual pet that
feeds on approvals. v1 deliberately ships the control surface only; the pet is what makes it
pleasant, not what makes it work.

- Species picker. Upstream ships 18 ASCII species plus GIF character packs. Worth having when
  there is a second species worth picking; the frame tables are already data, so adding one is
  a text edit.
- GIF rendering from local device storage. We will **not** implement the `char_begin` /
  `file` / `chunk` / `file_end` / `char_end` folder-push transfer: streaming GIFs over BLE
  exists because an ESP32 has no filesystem the user can reach. A phone does.
- Energy, and anything else that would make the pet a game rather than a readout.


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

## Shared clipboard

Shipped for text. What was looked at and left out, so it is not rediscovered:

- **Automatic phone-to-Mac with no gesture at all: not possible.** Measured on the Pixel rather
  than reasoned about, because it is the question everyone asks first — copying a code out of
  Google Authenticator and having it appear on the Mac is the single most wanted case, and it is
  the one that cannot be built:

  - `appops get dev.heywood8.claudebuddy READ_CLIPBOARD` already reports **allow**, and the
    reads are refused anyway. It is not an app-op gate that could be opened.
  - `ClipboardService` states the rule in the log verbatim: *application is not in focus nor is
    it a system service*. No permission or app-op is consulted.
  - `adb shell cmd clipboard get` → *No shell command implementation*. Even shell cannot read it.
  - `READ_CLIPBOARD_IN_BACKGROUND` is `signature`, so platform-signed apps only. `pm grant`
    does not reach it.

  That leaves the default IME (replace the daily keyboard with this — no), an accessibility
  service reading the screen (every password on every screen, forever — no), and a focusable
  overlay bubble, which does take window focus and would work but is still a tap, plus a
  permanent bubble and `SYSTEM_ALERT_WINDOW`. Strictly worse than the shade.

  So one tap is the floor, and what ships is the cheapest one: a **Send clipboard** action on
  the bridge notification, pointing at an activity because an activity has a window and a
  window takes focus. Plus the two that were already there — the dashboard capturing on focus,
  and an `ACTION_SEND` target.

- **Sharing while the dashboard sits behind it undoes itself.** `ShareActivity` sends text
  without putting it on this phone's clipboard, so when focus falls back to `MainActivity` the
  capture-on-focus sees the clipboard still holding something else and sends *that*, replacing
  what was just shared. Only reachable when the dashboard is the thing behind the share sheet,
  which is not how anybody shares. Left alone rather than fixed by having share also set the
  local clipboard, which would be a share sheet with a side effect nobody asked for.
- **Images and files: dropped.** The link moves a couple of kilobytes a second and the line cap
  is 8 KiB. A screenshot is three orders of magnitude out, and a chunked transfer for it would
  be the `char_begin`/`chunk`/`file_end` machinery already rejected above, for a payload nobody
  asked for.
- **History of clips: dropped.** One slot each way, replaced in place. A list would be a file of
  everything copied on a work machine sitting on a phone, which is the thing the concealed-type
  filter exists to avoid — building it deliberately at one remove is not better.
- **`EXTRA_IS_SENSITIVE` on arriving clips: not set.** It replaces the content of Android's paste
  confirmation with "Content hidden", and that confirmation is the only sign the phone gives
  that a clip arrived. What would justify hiding it never arrives: the Mac refuses to send
  anything marked concealed.
- **Negotiating the two off-switches: no.** `--no-clipboard` on the bridge and the phone's toggle
  are independent, so a bridge with mirroring on goes on sending clips to a phone that drops
  them. Wasteful by a few hundred bytes over an encrypted link, and the alternative is one
  device being able to overrule the other's decision to opt out.

Left for later, in rough order of how much they would be missed:

- **Pushing the current pasteboard on connect.** Rejected for now: a reconnect happens on every
  app update, reboot and walk out of range, and each one would overwrite the phone's clipboard
  with whatever the Mac last had. A clip copied while the phone was away is simply lost, which
  is what a clipboard does anyway.
- **A `cmbridge copy` / `cmbridge paste` pair**, for scripting and for the case where the grant
  is denied. The HTTP server on 8787 already has the shape for it — a `/clip` route next to the
  hook endpoints — and it would give the Mac half a path that needs no pasteboard read at all.
- **Saying on the phone that the Mac's grant is missing.** The bridge knows
  (`NSPasteboard.accessBehavior`) and only writes it to its own log at startup, so the symptom
  on the phone is a clipboard that never changes and never says why. It would need a field in
  `Snapshot`, which is the three-files-at-once rule.

## Protocol extensions

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

- Wear OS and iOS: **dropped**. One Pixel is the whole audience. Recorded so the reasoning is
  not rediscovered: a backgrounded iOS app drops the local name from its advertisement and
  moves service UUIDs into the overflow area, where a desktop scanner will not see them —
  which is also why the phone app is native Android rather than React Native.

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

## Review notes, 2026-08-27

A pass over both ends with the question "what would make this pleasanter to use". Grouped by
size rather than by side. The `why` field and the BLE connect timeout in the working tree at
the time of the pass are assumed landed.

Each item ends with a *Where / Wire / Done when* block, so that one can be picked up cold.
Two rules apply to all of them:

- Anything that changes what goes over the air — a field in `Snapshot`, `Prompt` or
  `Decision` — is edited in three places at once: `docs/PROTOCOL.md`,
  `bridge/Sources/cmbridge/Protocol.swift`, `android/.../Protocol.kt`. New fields get a
  default on the reading side, so a peer built before the field still decodes (the Kotlin
  side already does this with `= ""` / `= 0`; Swift needs `decodeIfPresent` or an optional).
  `docs/protocol/fixtures/vectors.json` pins ciphertexts for fixed plaintexts and does not
  need regenerating for a new field; it needs regenerating only if the sealed *bytes* change,
  which a schema addition does not cause.
- Anything that changes what the bridge sends back to Claude Code lives in `HookResponse`
  (`bridge/Sources/cmbridge/HookIO.swift`) and is covered by a case in
  `bridge/Tests/cmbridgeTests/QueueTests.swift`.

### Small, self-contained

- **Countdown from `expires`.** The protocol carries `prompt.expires` "so the phone can render
  a countdown rather than a spinner", and neither the dashboard nor the notification reads it.
  The notification gets it for free: `setWhen(expires * 1000)`, `setUsesChronometer(true)`,
  `setChronometerCountDown(true)`. The bubble gets a line — "the terminal takes over in
  27 min" — which is also the only place the half-hour window is ever explained to the user.

  *Where:* `Notifications.kt` `approval()`; `DashboardScreen.kt` `Bubble()`, only for
  `BubbleRole.ANSWERING` / `STANDALONE`. Compute against `Snapshot.now`, not the phone's
  clock — the phone-side convention is that durations are worked out in the host's frame.
  *Wire:* none; `expires` and `now` already exist.
  *Done when:* the shade shows a live countdown that reaches zero at `expires`; the bubble
  shows the remaining minutes and re-renders with each keepalive; `expires == 0` (an older
  bridge) shows neither.

- **`cmbridge status` does not know whether a phone is linked.** It reports the identity, the
  port, the hooks, and nothing about the link — which is the first question when the phone is
  quiet.

  *Where:* `HookServer.swift` `/health` (currently `{"ok":true}`); `main.swift` `case
  "status"` and `probe()`. `Coordinator` already knows `link.isLinked` and `queueDepth`; it
  needs a device name, which arrives in the phone's `ready` frame — `SecureLink.swift` is
  where it is parsed and dropped today.
  *Wire:* none over BLE. The local HTTP body becomes
  `{"ok":true,"linked":<bool>,"device":"<model or empty>","waiting":<int>,"since":<unix or 0>}`.
  *Done when:* `status` prints a `Phone     :` line reading `linked (Pixel 9) since 10:42,
  0 waiting` or `not linked`; existing `"ok"` substring check in `probe()` keeps passing.

- **`PostToolUse` matcher is narrower than `PermissionRequest`.** The installer sets
  `Bash|Write|Edit|Task` on the post-tool hooks, while the permission hook fires for every
  tool. A `MultiEdit`, `NotebookEdit` or `WebFetch` allowed in the terminal produces no
  `PostToolUse` the bridge hears, so the card sits on the phone until the turn ends.

  *Where:* `HookInstaller.swift` `hooks(window:)` — drop `matcher` on `PostToolUse` and
  `PostToolUseFailure`; `Coordinator.recordToolUse` — keep calling `resolveElsewhere` for
  every tool, but only `entries.insert` for the set that used to be matched (make it a
  static list next to `defaultSkippedTools`). `HookInstallerTests.swift` has the snippet
  pinned and will need its expectation updated.
  *Wire:* none.
  *Done when:* a `WebFetch` request allowed in the terminal disappears from the phone on the
  tool's `PostToolUse`, and the recent-calls feed still shows only Bash/Write/Edit/Task.
  Re-running `install-hook` replaces the old matched entry rather than appending (the
  `isOurs` merge already keys on the URL, so this should be free — verify it).

### Features

- **Allow for the rest of the session.** Two verdicts exist, `once` and `deny`. The
  `PermissionRequest` hook response accepts `updatedPermissions` next to `allow`, and that is
  what a third button would send: "allow `git push` until this session ends". Most repeat
  buzzes in an evening are the same command with a different argument.

  Documented response shape (hooks reference, checked 2026-08-27):

  ```json
  {"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{
    "behavior":"allow",
    "updatedPermissions":[{"type":"allow","rules":["Bash(git push:*)"],
                            "behavior":"allow","destination":"session"}]}}}
  ```

  `destination` is `"session"` or `"user"`; use `"session"` only — writing into the user's
  settings from a phone tap is not a decision this button should be able to make. The rule
  string follows the `permissions.allow` syntax.

  *Where:* `Protocol.kt` / `Protocol.swift` — `Verdict` gains `session` (`"session"` on the
  wire). `HookIO.swift` — `HookResponse.allow(rules: [String])` and a
  `HookRequest.permissionRule` derived from `tool_input`: for `Bash`, `Bash(<first word>:*)`
  — two words when the first is `git`, `npm`, `docker`, `make`, `gh`, `cargo`, `go`;
  for `Write`/`Edit`, `Edit(<file_path>)`; for anything else, `<Tool>` alone. Show the derived
  rule on the phone — put it in `Prompt` as `rule` — so the button says what it grants:
  "Allow `git push:*` this session". `Notifications.kt` — third action (three is the
  platform maximum, and it is the last slot). `DashboardScreen.kt` `Rail()` and `Bubble()`
  — a third button, visually weaker than Allow. `Journal.kt` — `outcome` gains `"session"`;
  `HistoryScreen.label()` renders it.
  *Wire:* `Decision.decision` accepts `"session"`; `Prompt.rule: String = ""`. PROTOCOL.md
  decision table gets a third row. An old bridge receiving `"session"` fails to decode the
  verdict: `LineCodec.decode` throws `unrecognised`, `main.swift` logs "bad line from phone"
  and the link stays up — so the tap is lost, not the session. Acceptable for a
  phone-newer-than-bridge mismatch; nothing to add.
  *Done when:* tapping the third button unblocks the terminal, the same command in the same
  session no longer reaches the phone, a new session asks again, and `~/.claude/settings.json`
  is untouched. A `QueueTests` case pins the response JSON; a `HookIO` test pins the rule
  derivation for the listed tools.

- **Deny with a note.** `HookResponse.deny("Denied from phone")` is text the model reads.
  A denial that carries a sentence — "not until the tests pass" — turns the phone from a
  switch into a steering channel.

  *Where:* `Protocol.kt` / `Protocol.swift` — `Decision.note: String = ""`, capped at
  200 bytes on the phone (same byte-clamp idea as `Prompt.whyLimit`). `Coordinator.resolve`
  passes it through; `HookResponse.deny(message)` becomes `"Denied from phone: <note>"` when
  non-empty, unchanged when empty. `Notifications.kt` — `RemoteInput` on the Deny action
  (`setAuthenticationRequired` stays); `DecisionReceiver` reads
  `RemoteInput.getResultsFromIntent`. `DashboardScreen.kt` — long-press on Deny opens a
  small sheet with a text field and three preset chips ("run the tests first", "ask me in the
  terminal", "not this file"); plain tap stays a plain deny. `Journal.Entry` gains `note`.
  *Wire:* `Decision` gains an optional `note`; the bridge must accept its absence. PROTOCOL.md
  decision section shows the field.
  *Done when:* a denial with a note appears in the terminal as the deny message and Claude's
  next turn visibly reacts to it; a denial without a note is byte-identical to today's.

- **Ping when a turn ends.** The `Stop` hook already reaches the bridge and sets
  `session.finished`; the phone shows it and stays silent. Opt-in, low-importance — the
  reason one walks away from the laptop in the first place. Claude Code's `Notification`
  hook (`idle_prompt`: a session has waited a minute for input) is the same idea from the
  other side.

  *Where:* phone only for the `Stop` half — `BuddyService.onSnapshot` compares each
  session's `finished` with the previous snapshot's; a `0 → non-zero` transition on a session
  raises a notification on a new channel `CHANNEL_DONE` (`IMPORTANCE_DEFAULT`, no vibration
  by default), title "Finished", text `shortPath(cwd)` + `task`, id derived from the session
  id so several sessions do not overwrite each other. `Settings.kt` — `pingWhenDone`,
  default off, switch in the dashboard's settings sheet next to "Light up the screen".
  Suppressed while `BuddyState.foreground`. For the `idle_prompt` half: `HookInstaller` gains
  `Hook(event: "Notification", path: "notification", matcher: "idle_prompt")`;
  `HookServer` routes it to `Coordinator.noteIdle(sessionID)`, which sets a new
  `SessionSummary.idle: Int` stamp; the phone treats it like `finished`.
  *Wire:* `SessionSummary.idle: Long = 0` for the second half only.
  *Done when:* with the toggle on and the app in the background, a session finishing its turn
  produces one notification naming the project; toggling off produces none; the approval
  channel is unaffected.

- **A risk class on the prompt.** The bridge tags the request from `hint` and the phone draws
  a high-risk request differently. Aimed at the thumb that taps without reading; it is the
  same failure the unlock requirement guards against, one layer up.

  *Where:* `HookIO.swift` — `HookRequest.risk: String`, `""` or `"high"`, from a static
  list of patterns over the raw command (not the truncated hint): `rm -rf`, `rm -r`,
  `git push --force` / `-f`, `git reset --hard`, `git clean`, `sudo`, `| sh`, `| bash`,
  `curl … | `, `chmod -R`, `> /dev/`, `mkfs`, `dd if=`; for Write/Edit, a path outside `cwd`
  and outside `~/.claude`. Patterns live in one array with a test each. `Prompt.risk`.
  Phone: `Bubble()` uses `errorContainer` for the bubble colour and prefixes the title with
  "Careful:"; `Notifications.approval` uses a second channel `CHANNEL_APPROVAL_RISKY` with its
  own vibration pattern, so the user can tell them apart by feel and tune them separately.
  The in-app Allow for a risky request is disabled for the first second after the bubble
  appears — long enough to break a reflex, short enough not to annoy.
  *Wire:* `Prompt.risk: String = ""`.
  *Done when:* `rm -rf build` reaches the phone red, buzzes differently, and its Allow is
  briefly inert; `ls` is unchanged; every pattern has a `HookIO` test; nothing here changes
  what the bridge answers — the class is advice, never a decision.

- **A diff for Edit and Write.** `HookRequest.summarise` reduces an Edit to its `file_path`,
  so the phone is asked to approve "Edit App.js" and nothing more.

  *Where:* `HookIO.swift` `summarise()`. For `Edit`: `<file_path>\n−<old lines> +<new lines>`
  followed by the first three lines of `new_string`; for `Write`: `<file_path>\n<line count>
  lines` and the first three lines of `content`; for `MultiEdit`: the path and the number of
  edits. All within the existing 512-byte `hintLimit` — the clamp already handles overflow.
  Keep the `PostToolUse` side in mind: `resolveElsewhere` matches the shown hint against the
  reported one with `sameCommand`, which is a prefix test — the reported hint for the same
  edit must be produced by the same function, so this stays consistent by construction, but
  add a test that a PermissionRequest hint and the matching PostToolUse hint still match.
  *Wire:* none; `hint` is free text.
  *Done when:* an Edit request on the phone shows the file, the line delta and a glimpse of
  the new text; the stale-card clearing on `PostToolUse` still works for Edit and Write
  (covered by a `QueueTests` case).

- **Tapping a request to read all of it.** The command on screen is cut off and there is no way
  to see the rest. Deciding on a command you can only see the first six lines of is the one
  thing this screen exists to prevent, and `rm -rf` is at its most interesting past the
  ellipsis.

  It is two truncations with the same symptom, and they cost very different amounts:

  1. **On screen.** `Bubble()` draws `hint` at `maxLines = 6`, or 3 for a queued one, with
     `TextOverflow.Ellipsis`. The phone is already holding the whole 512 bytes — nothing is
     missing, it is only not drawn. Tapping to expand costs no wire change at all and fixes
     every request whose command is under the limit, which is nearly all of them.
  2. **On the wire.** `Prompt.hintLimit` cuts `hint` to 512 bytes and `whyLimit` cuts `why` to
     160, in `Prompt.truncatingHint` on the bridge, before either leaves the Mac. Past that the
     phone never received the text and no amount of tapping will produce it.

  Do (1) first and alone. It is most of the value, and it is the half that can ship without
  touching the protocol.

  *Where (1):* `DashboardScreen.kt` `Bubble()` — a `var expanded by remember(front.id)`,
  `Modifier.clickable` on the `Surface`, `maxLines = if (expanded) Int.MAX_VALUE else …`.
  Reset per request id, so the next one does not arrive already unrolled. The bubble is inside
  a `verticalScroll`, so a long one scrolls rather than pushing the rail off screen — worth
  checking in the wide layout, where the rail is a separate column and does not move. The
  notification is already ahead of the screen here: `Notifications.approval` uses
  `BigTextStyle` with `why`, `hint` and `cwd` in full, so the shade shows more than the app.
  *Wire (1):* none.
  *Done when:* tapping a bubble shows the whole hint and tapping again folds it back; a queued
  bubble expands too; answering and being handed the next request draws it folded.

  *Where (2):* `Protocol.swift` / `Protocol.kt` `hintLimit`. Raising it is one number, and the
  8 KiB line cap is the real ceiling — a snapshot carries the head plus everything queued, so
  the budget is per queue, not per prompt, and `Clip` has already spent the headroom arithmetic
  once (see the clip section in `PROTOCOL.md`). Sending the full command only for the request
  being expanded, on request from the phone, is the version that does not blow the cap; that
  needs a new message in both directions and is a bigger job than it first looks.
  *Wire (2):* `hint` grows, or a `{"t":"full","id":…}` request and its answer.
  *Done when:* a command longer than 512 bytes can be read to the end on the phone, and a queue
  of several long requests still fits in one snapshot.

### Looked at and left alone

- Token cost in currency. The transcript format carries no guarantee and the token figures
  are already the one part of the snapshot allowed to be wrong; a price on top of them
  doubles the surface for being wrong about decoration.
- Everything already recorded above — multiple hosts, Wear OS, PAKE, the species picker.
  The reasoning there still holds.
