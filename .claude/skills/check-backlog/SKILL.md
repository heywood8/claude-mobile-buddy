---
name: check-backlog
description: Pick the next thing to build in claude-mobile-buddy. Reads backlog.md, drops what the author already killed, drops what has quietly landed, ranks what survives, and proposes three items with what/why/risk before asking how you want it done. Use this whenever the user asks what to work on next, what is left, what is worth picking up, says "/check-backlog", "что дальше", "что там в бэклоге", "возьми задачу из бэклога", or wants an item from backlog.md implemented — even when they name the item themselves, because the staleness and risk checks still apply.
---

# Check backlog

`backlog.md` is half a proposal list and half a graveyard. The graveyard is deliberate: the file
exists so that decisions "are not silently rediscovered". So the job here is not to summarise the
file — it is to work out which handful of lines in it are still true, still undone, and worth an
evening, and to hand the user a choice rather than a lecture.

Answer in the language the user wrote in. Keep identifiers, paths and `Done when` wording verbatim
in whatever language you are writing — they are the contract with the code.

## 1. Read the whole file

Read `backlog.md` top to bottom. It is ~340 lines; that is cheap. Do not grep for candidates: the
sentence that kills an item usually lives in a different paragraph from the item itself, and a grep
that finds "SPAKE2" will not find "v1 ... needs no PAKE".

The file is not the only place the reasoning lives. This repo argues in comments — a typed
six-digit code is rejected in `Handshake.swift`, not in `backlog.md`, and the rule that forces a
third independent implementation before `vectors.json` can be regenerated lives in
`docs/protocol/fixtures/README.md`. Before calling anything dead or live, grep the code for the
same subject. A design note sitting next to the implementation outranks your reading of the
backlog.

Run `git status --short` and `git diff --stat backlog.md` in the same breath. The plan itself is
sometimes uncommitted — the whole `Review notes` section arrived that way — and work built on an
unversioned plan is worth one sentence of warning, once, not a paragraph.

## 2. Sort every item into three buckets

**Dead.** Marked `**dropped**`, sitting under "Looked at and left alone", or written as a decision
("Dropped, not merely deferred", "We will **not** implement", "One Pixel is the whole audience").
Never propose these, and never propose a smaller version of one. Re-proposing something the author
buried with reasons is the single worst thing this skill can do — it makes the file worthless.

But a buried decision killed a *solution*, not the *problem* it was aimed at. When the user turns
up wanting one — "the camera annoys me, wasn't there something about a six-digit code" — the honest
answer has two halves: the decision stands and here is the recorded reason, **and** here is what
actually closes the pain without reopening it. That second half is usually already half-built:
`cmbridge pair --url` already prints the payload and both pairing parsers already take a string, so
the camera goes away with an intent filter and no protocol change at all. Look for that before you
reach for the picker. Offering a menu in place of an answer reads as a dodge, and it is one.

**Gated.** Deferred behind a condition rather than behind priority: Swift 6 language mode
("revisit on a later toolchain"), the species picker ("worth having when there is a second species
worth picking"), instrumented tests (no runner has a BLE peripheral role). A gated item is
proposable only if you checked the gate and it has opened — run the check (`swift --version`, look
at the toolchain, whatever the gate actually is) and show the command. An unchecked gate is a dead
item.

**Live.** What is left. In practice this is the `Review notes` section, where every item carries a
*Where / Wire / Done when* block precisely so it "can be picked up cold".

## 3. Check each live item against the code before you believe it

The backlog is written ahead of the code and goes stale in both directions — items land without
the file being updated (`docs: strike what is built and what is not wanted`, `docs: bring the
backlog back in line with what was built` are both real commits here), and `Where:` lines drift
as files move.

The `Where:` line names the file and the function. Go look. One or two greps per item, not a full
read — you are answering "is this still undone", not "how do I build it".

Path shorthand used in the backlog:

| Written as | Actually |
|---|---|
| `android/.../Foo.kt` | `android/app/src/main/kotlin/dev/heywood8/claudebuddy/Foo.kt` |
| `Coordinator.swift`, `HookIO.swift`, … | `bridge/Sources/cmbridge/` |
| `QueueTests.swift`, `HookInstallerTests.swift` | `bridge/Tests/cmbridgeTests/` |

Then:

- Behaviour already present → the item is **landed**. Drop it from the ranking and report it in
  the tail, so the user can strike it from the file.
- File or symbol moved → keep the item, but say so in the proposal. The `Where:` line will send
  whoever picks it up to the wrong place.
- The code is worse than the note → say that too, and loudly. `DecisionReceiver` is recorded as
  losing the verdict into a null sink; it also cancels the notification unconditionally straight
  afterwards, which takes away the only way to try the tap again. Finding that changes both the
  item's size and its `Done when`, and it is the most valuable thing this step produces.
- Working tree matters: `git status --short` and `git diff --stat` first. The backlog's own
  assumptions ("assumed landed") can refer to uncommitted work.

## 4. Rank what survives

In priority order, and none of these is a tiebreak-by-vibes:

1. **A defect beats a polish.** Items under "Bugs that read as UX" are things the author already
   considers broken; leaving them in favours nothing.
2. **Pickable cold beats prose.** A full *Where / Wire / Done when* block means the design argument
   is already had. A bare prose bullet needs a design conversation, which is a different task and a
   worse thing to hand someone who asked "what should I do next".
3. **Finishable here beats finishable on the Pixel.** CI and this machine can compile both sides and
   run unit tests; nothing can exercise BLE. An item whose `Done when` can only be observed on the
   phone is still worth proposing, but say that the acceptance step is manual.
4. **Spread the three across sizes.** Three L-sized items is not a choice, it is a wall. The user
   picks by how much evening is left, so give them that axis.

Size, anchored in what this repo actually costs:

- **S** — one file, one function, no wire change, no new test file.
- **M** — several files on one side, plus a case in an existing test.
- **L** — the wire moves: `docs/PROTOCOL.md`, `bridge/Sources/cmbridge/Protocol.swift` and
  `android/.../Protocol.kt` edited in lockstep, a default on the reading side so an older peer still
  decodes, and an answer for what a newer phone does to an older bridge.

## 5. Present exactly three, as a choice

Ask with `AskUserQuestion`, single-select, one option per item. The picker is the point — the user
answers with an arrow key, and each item carries its own detail in the preview pane instead of a
wall of text above the question. Print nothing before the question except the tail lines below.

- `header` — the subject, 12 characters: `Sessions`, `Notif tap`, `Session allow`.
- `label` — the backlog's own title trimmed to a few words, with the size marker:
  `Sessions never die (M)`.
- `description` — one line: the consequence today. This is what gets read while scrolling.
- `preview` — the detail, as four labelled lines. It renders as monospace markdown, so keep lines
  short and skip tables:

```
What:    the change, in the user's terms, not the layer's.
Why:     the consequence today, taken from the backlog's own reasoning — it is usually one
         line and better than anything you would invent.
Risk:    from the catalogue below, or "none beyond the usual" — say that when it is true
         rather than manufacturing a risk.
Cost:    what the acceptance loop costs, not what the diff costs.
Checked: the evidence it is still undone — file:line, or the grep you ran.
```

One or two sentences per line. The user is choosing, not reading a spec.

`Cost` is there because the question is usually "what do I do with this evening", and the diff is
the small half. A six-line change in `Notifications.kt` still costs a release-signed APK, an
install and a relaunch — the phone runs a build signed with the key in `~/.config/claude-mobile-buddy`
(see `.gitignore`), a debug build cannot replace it, and uninstalling to get around that wipes the
keyring and forces re-pairing. A one-function change in the bridge still costs whatever the local
SwiftPM breakage in § Toolchain costs. Name the loop, not the line count.

Do not add a "none of these" option: the picker always offers Other, and that is where "show me the
rest" and "I want something else entirely" arrive. Treat Other as a request to widen the list, not
as a reason to stop.

Risks worth naming, because they are the ones this repo actually has:

- **Wire drift.** A field added on one side only. The reading side needs a default
  (`decodeIfPresent` in Swift, `= ""` / `= 0` in Kotlin); `docs/protocol/fixtures/vectors.json`
  needs regenerating only if the sealed *bytes* change, which adding a schema field does not cause.
  A verdict string an older bridge does not know makes `LineCodec.decode` throw — the tap is lost,
  the link survives.
- **The terminal stays blocked.** Anything touching `HookResponse` / `HookServer` sits on the path
  that unblocks `claude`. A wrong shape does not fail loudly, it hangs a human's terminal until the
  timeout. Pin the JSON in `QueueTests.swift`.
- **Only the Pixel can tell you.** No GitHub runner and no emulator provides a BLE peripheral role.
- **Notification channels are set once.** Importance and vibration of an existing channel cannot be
  changed by code after creation — the user owns them. New behaviour needs a new channel id, or it
  silently inherits the old settings.
- **It edits `~/.claude/settings.json`.** The install-hook path merges into a file the user owns and
  keeps a `.bak`. Anything that changes the merge risks a stale or duplicated entry.
- **It widens what one tap can do.** Session-scoped allows, notes the model reads, anything that
  turns the phone from a switch into a steering channel. `destination: "user"` is out of bounds by
  the backlog's own rule.

Then one tail line each, only where non-empty:

```
Landed since the note: <item> (<evidence>) — worth striking from backlog.md
Gated: <item> — <gate>, checked with `<command>`, still shut
Dead, not re-proposed: <count> items
```

## 6. Ask only where the fork is real

After the user picks, the default is to start, not to interview. 229 lines were written so this
conversation could be short.

Ask only when one of these is true:

- The item text offers alternatives — "Minimum: … Better: …" is an explicit fork.
- It has halves that can ship separately (the `Stop` ping and the `idle_prompt` hook are one item
  and two changes, one of which needs a wire field).
- A default is genuinely unnamed. Named ones are decided: `--session-idle` defaults to 6 hours
  because "a session parked overnight is not dead". Do not re-ask that.
- The code contradicts the note, and the answer changes the shape of the work.

At most three questions, each with a recommended default stated, so that "да" is a complete answer.
Use `AskUserQuestion` when the options are discrete. If nothing qualifies, say what you are about
to do in one line and get on with it.

## 7. Plan, confirm, build, strike

State the plan as: files to touch, and the item's own `Done when` restated as the acceptance check —
it is already written as one. Confirm before the first edit.

Two rules from the backlog apply to every item and are not optional:

- Anything that changes what goes over the air is edited in `docs/PROTOCOL.md`,
  `Protocol.swift` and `Protocol.kt` **at once**, with a default on the reading side.
- Anything that changes what the bridge answers Claude Code lives in `HookIO.swift` and gets a case
  in `bridge/Tests/cmbridgeTests/QueueTests.swift`.

Before promising a test as the acceptance check, look at what the test setup can actually reach.
`android/app/build.gradle.kts` pulls in junit and kotlinx-serialization and no Robolectric, so
anything that builds a `Notification` is not unit-testable and `gradlew test` proves compilation and
nothing else. Promising a green test that cannot exist is worse than saying plainly that this one is
checked by eye on the phone.

Verify with `make -C bridge test` and `./android/gradlew -p android test` (never `cd` into the
module — the Gradle wrapper takes `-p`). The local Swift toolchain has caveats that CI does not;
`backlog.md` § Toolchain has them. If the acceptance check needs the phone, say so plainly instead
of implying a green test proves it.

When it lands, strike the item from `backlog.md` in the same change — the file is only useful while
it is true, and this repo keeps it that way on purpose.
