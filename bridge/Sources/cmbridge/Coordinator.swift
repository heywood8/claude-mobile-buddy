import Foundation

/// Whatever carries lines to the phone.
///
/// The coordinator only needs to know whether there is a link and how to push a line down it.
/// Keeping that behind a protocol is what lets the queue be tested without a radio.
protocol LinkSink: AnyObject, Sendable {
    var isLinked: Bool { get }
    func send(_ line: Data)
}

/// Holds the state the phone renders and arbitrates approvals one at a time.
///
/// Requests queue in arrival order; the phone shows the head and a count of what is behind it.
/// Every request carries its own deadline measured from when it arrived, not from when it
/// reached the front — see `window`.
actor Coordinator {
    /// Default time a request may wait for the phone before the terminal takes over.
    ///
    /// Half an hour, because the point of carrying the approval in your pocket is that you can
    /// be in a meeting when it arrives. The hook's own timeout has to be set above this or it
    /// gives up first — `print-hook` derives it from whatever window the bridge is running.
    ///
    /// The clock starts on arrival rather than at the head of the queue. Starting it at the head
    /// would make total latency grow with queue depth and reintroduce exactly the indefinite
    /// wait that failing open exists to prevent.
    static let defaultWindow: TimeInterval = 30 * 60

    /// This bridge's window. Read from outside the actor, so it never changes after init.
    nonisolated let window: TimeInterval

    /// How long this bridge waits for a vanished phone. Injected so tests need not sit
    /// through half a minute to watch the queue let go.
    nonisolated let linkGrace: TimeInterval

    /// Tools whose permission prompt cannot be usefully answered from a phone.
    ///
    /// Approving AskUserQuestion does not answer the question — it only lets the terminal ask
    /// it. Sending that to the phone is not merely useless: the screen shows one request at a
    /// time, so it would sit there blocking approvals that *can* be answered remotely.
    nonisolated let skippedTools: Set<String>

    static let defaultSkippedTools: Set<String> = ["AskUserQuestion", "ExitPlanMode"]

    /// How long a vanished phone has to come back before the queue is let go.
    ///
    /// Longer than a reconnect takes and far shorter than the window, so a flicker costs a few
    /// seconds of nothing and a phone genuinely left behind still fails open.
    static let defaultLinkGrace: TimeInterval = 30

    private struct Pending {
        let id: String
        let sessionID: String
        let prompt: Prompt
        let continuation: CheckedContinuation<Decision.Verdict?, Never>
    }

    private let link: any LinkSink
    private let log: Logger

    private struct Session {
        let cwd: String
        let started: Int
        var active: Int
        var decided: Int
        /// Tokens the model has processed for this session, as far as its transcript says.
        var tokens: Int = 0
        /// The last thing you asked it for.
        var task: String = ""
        /// When it last stopped answering. Zero while it is working.
        var finished: Int = 0
    }

    private var queue: [Pending] = []
    private var entries: [String] = []
    private var sessions: [String: Session] = [:]
    private var transcripts = TranscriptReader()

    /// The day each transcript was first read, which is what "today" is counted against.
    ///
    /// A transcript, not a session: the totals are per file, and a session that ends and is
    /// resumed keeps writing to the same one. Sessions come and go from `sessions` — including
    /// on SessionEnd, taking their tokens with them — so the day has to be remembered against
    /// something that stays.
    private var transcriptDay: [String: Int] = [:]

    /// The last decision taken anywhere but the phone. Kept until something replaces it; the
    /// phone decides for itself how long it is still worth reacting to.
    private var resolved: Resolution?

    /// Bumped on every link change, so a grace timer can tell whether it still speaks for the
    /// disconnection it was started for.
    private var linkGeneration: UInt64 = 0

    init(
        link: any LinkSink,
        log: Logger,
        window: TimeInterval = Coordinator.defaultWindow,
        skippedTools: Set<String> = Coordinator.defaultSkippedTools,
        linkGrace: TimeInterval = Coordinator.defaultLinkGrace
    ) {
        self.link = link
        self.log = log
        self.window = window
        self.skippedTools = skippedTools
        self.linkGrace = linkGrace
    }

    // MARK: - Session bookkeeping

    func sessionStarted(_ id: String, cwd: String) {
        let now = Self.now()
        sessions[id] = Session(cwd: cwd, started: now, active: now, decided: 0)
        pushSnapshot()
    }

    func sessionEnded(_ id: String) {
        sessions[id] = nil
        // Nobody is left to run whatever it was asking about.
        for pending in queue where pending.sessionID == id {
            withdraw(pending.id, reason: "its session ended")
        }
        pushSnapshot()
    }

    func recordToolUse(sessionID: String, cwd: String, tool: String, hint: String) {
        // A tool that has just run was allowed by somebody, and if it is still on the phone
        // then that somebody was the terminal. There is no event for the moment a person
        // answers the prompt over there — the flow is PreToolUse, PermissionRequest, silence,
        // PostToolUse — so the tool having run is the earliest proof available, and it arrives
        // only once the tool finishes. A ninety-second command is a ninety-second stale card,
        // and nothing in the hook API can shorten that.
        resolveElsewhere(sessionID: sessionID, tool: tool, hint: hint, how: "allowed")

        entries.insert(entryLine(tool: tool, hint: hint), at: 0)
        if entries.count > Self.entryLimit { entries.removeLast(entries.count - Self.entryLimit) }
        touch(sessionID, cwd: cwd)
        pushSnapshot()
    }

    /// What you last asked a session to do.
    ///
    /// The tool calls say what it is doing; this says what it was told to do, which is the
    /// thing you have forgotten by the time the phone buzzes an hour later.
    func noteUserPrompt(sessionID: String, cwd: String, text: String) {
        let task = SessionSummary.trimTask(text)
        guard !task.isEmpty else { return }
        touch(sessionID, cwd: cwd)
        sessions[sessionID]?.task = task
        pushSnapshot()
    }

    /// Drops a queued request that somebody has already answered somewhere else, and remembers
    /// which way it went so the phone can show it.
    func resolveElsewhere(sessionID: String, tool: String, hint: String, how: String) {
        guard let stale = queue.first(where: {
            $0.sessionID == sessionID && $0.prompt.tool == tool &&
                Self.sameCommand($0.prompt.hint, hint)
        }) else { return }

        resolved = Resolution(id: stale.id, session: sessionID, how: how, at: Self.now())
        withdraw(stale.id, reason: "\(how) in the terminal")
    }

    /// The turn is over, so nothing it was asking about is still being waited on.
    ///
    /// Weaker than the tool-use signal and worth having anyway: a request denied in the
    /// terminal produces no event of its own — the tool never runs — and would otherwise sit
    /// on the phone until the window ran out.
    func turnEnded(_ sessionID: String) {
        if var session = sessions[sessionID] {
            session.finished = Self.now()
            sessions[sessionID] = session
        }
        for pending in queue where pending.sessionID == sessionID {
            resolved = Resolution(id: pending.id, session: sessionID, how: "gone", at: Self.now())
            withdraw(pending.id, reason: "the turn ended without it")
        }
    }

    /// The command as the phone was shown it may have been truncated; the one reported after
    /// the fact never is.
    private static func sameCommand(_ shown: String, _ reported: String) -> Bool {
        if shown == reported { return true }
        let trimmed = shown.hasSuffix("…") ? String(shown.dropLast()) : shown
        return !trimmed.isEmpty && reported.hasPrefix(trimmed)
    }

    /// Sessions are also learned from any hook that mentions one. SessionStart does not always
    /// arrive first — a bridge restarted mid-session would otherwise never hear about the
    /// sessions already running.
    private func touch(_ id: String, cwd: String) {
        let now = Self.now()
        if var session = sessions[id] {
            session.active = now
            // Anything at all from a session means it is going again, and whatever it said
            // last is no longer the thing it is waiting on you about.
            session.finished = 0
            sessions[id] = session
        } else {
            // Logged once per session, so it is possible to tell "the hook never fired" from
            // "the hook fired and the phone is not showing it" without guessing.
            log.info("session \(id.prefix(8)) seen at \(cwd)")
            sessions[id] = Session(cwd: cwd, started: now, active: now, decided: 0)
        }
    }

    private static func now() -> Int { Int(Date().timeIntervalSince1970) }

    private static func dayKey() -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar.ordinality(of: .day, in: .era, for: Date()) ?? 0
    }

    /// Counts whatever the transcript has gained since the last hook from this session.
    ///
    /// Called from the hook path rather than on a timer: the file only moves when the session
    /// does, and every hook that reaches us names the transcript it came from.
    func noteTranscript(sessionID: String, path: String) {
        guard !path.isEmpty else { return }
        let total = transcripts.total(path: path)
        guard total > 0 else { return }

        if transcriptDay[path] == nil { transcriptDay[path] = Self.dayKey() }
        if var session = sessions[sessionID] {
            session.tokens = total
            sessions[sessionID] = session
        }
    }

    /// Everything read today, by the day the bridge first opened each transcript.
    ///
    /// Not by the timestamps inside it: that would mean trusting one more field of a format
    /// with no guarantees, to sharpen a number that is decoration. A session running since
    /// yesterday counts as today's on the first day the bridge sees it, and no day counts
    /// anything twice.
    private func tokensToday() -> Int {
        let today = Self.dayKey()
        return transcriptDay
            .filter { $0.value == today }
            .keys
            .reduce(0) { $0 + transcripts.tokens(for: $1) }
    }

    // MARK: - Approvals

    func decide(_ request: HookRequest) async -> HookResponse {
        guard !skippedTools.contains(request.toolName) else {
            log.decision("\(request.toolName): not answerable from a phone, deferring to terminal")
            return .noDecision
        }
        guard link.isLinked else {
            log.decision("\(request.toolName): no phone linked, deferring to terminal")
            return .noDecision
        }

        let id = Self.newRequestID()
        let deadline = Date().addingTimeInterval(window)
        let prompt = Prompt.truncatingHint(
            id: id,
            session: request.sessionID,
            tool: request.toolName,
            hint: request.hint,
            cwd: request.cwd,
            expires: Int(deadline.timeIntervalSince1970))

        touch(request.sessionID, cwd: request.cwd)
        log.decision("\(id) \(request.toolName) asked — \(request.hint)")

        // Cancellation matters as much as the answer: when Claude Code abandons the request —
        // you pressed escape, or answered in the terminal after the bridge gave up — the phone
        // must stop showing a decision nobody is waiting for. A stale card is worse than no
        // card, because tapping it looks like it did something.
        let verdict = await withTaskCancellationHandler {
            await withCheckedContinuation { (continuation: CheckedContinuation<Decision.Verdict?, Never>) in
                queue.append(Pending(
                    id: id,
                    sessionID: request.sessionID,
                    prompt: prompt,
                    continuation: continuation))
                pushSnapshot()
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(self?.window ?? Coordinator.defaultWindow) * 1_000_000_000)
                    await self?.expire(id)
                }
            }
        } onCancel: {
            Task { [weak self] in await self?.withdraw(id, reason: "caller went away") }
        }

        switch verdict {
        case .once:
            log.decision("\(id) allowed from phone")
            return .allow
        case .deny:
            log.decision("\(id) denied from phone")
            return .deny("Denied from phone")
        case nil:
            // Whoever released it has already said why — expired, withdrawn, phone gone,
            // bridge shutting down. Saying "expired" here as well would put the wrong reason
            // in the log for three of the four.
            return .noDecision
        }
    }

    /// Accepts an answer for anything still queued, not only the head.
    ///
    /// The head is what the phone shows, but a decision can arrive for a request that has just
    /// been overtaken — that is a race, not an error.
    func resolve(_ decision: Decision) {
        guard let index = queue.firstIndex(where: { $0.id == decision.id }) else { return }
        let pending = queue.remove(at: index)
        if var session = sessions[pending.sessionID] {
            session.decided = Self.now()
            sessions[pending.sessionID] = session
        }
        pending.continuation.resume(returning: decision.decision)
        pushSnapshot()
    }

    func linkChanged(_ up: Bool) {
        log.info(up ? "phone linked" : "phone unlinked")
        linkGeneration &+= 1
        if up {
            pushSnapshot()
            return
        }
        // A dropped link is usually a reconnect a few seconds later: the phone carried out of
        // range, the radio hiccuping, the app being replaced by an update. Measured here, the
        // gap between "link down" and "phone linked" was three to five seconds every time.
        //
        // Releasing the queue at the first flicker turns that into a decision taken somewhere
        // you were not looking — the terminal prompts, and the request you were about to
        // answer in your hand is gone. So the queue waits. Against a window of half an hour,
        // half a minute costs nothing.
        let generation = linkGeneration
        let grace = linkGrace
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(grace * 1_000_000_000))
            await self?.releaseIfStillGone(generation)
        }
    }

    /// Gives up on the phone, if it has not come back in the meantime.
    func releaseIfStillGone(_ generation: UInt64) {
        // Any link change since scheduling this makes it stale: either the phone returned, or
        // it left again and a newer timer owns the decision.
        guard generation == linkGeneration, !link.isLinked else { return }
        let stranded = queue
        queue.removeAll()
        for pending in stranded {
            log.decision("\(pending.id) released, phone did not come back")
            pending.continuation.resume(returning: nil)
        }
    }

    func keepalive() {
        if link.isLinked { pushSnapshot() }
    }

    // MARK: - Internals

    /// Releases everything still waiting, for a bridge that is about to stop existing.
    ///
    /// Exiting with requests in flight hangs up on the hooks holding them open, and a broken
    /// connection is not an answer — the session on the other end has to decide what a dead
    /// socket meant. Released, it is the same "no decision" an expiring window produces, and
    /// the terminal asks for itself.
    func drain(reason: String) {
        let stranded = queue
        queue.removeAll()
        for pending in stranded {
            log.decision("\(pending.id) released — \(reason)")
            pending.continuation.resume(returning: nil)
        }
    }

    /// Drops a request nobody is waiting for any more.
    func withdraw(_ id: String, reason: String) {
        guard let index = queue.firstIndex(where: { $0.id == id }) else {
            log.info("withdraw \(id): not queued any more")
            return
        }
        let pending = queue.remove(at: index)
        log.decision("\(id) withdrawn — \(reason)")
        pending.continuation.resume(returning: nil)
        pushSnapshot()
    }

    private func expire(_ id: String) {
        guard let index = queue.firstIndex(where: { $0.id == id }) else { return }
        let pending = queue.remove(at: index)
        log.decision("\(id) expired unanswered, deferring to terminal")
        pending.continuation.resume(returning: nil)
        pushSnapshot()
    }

    private func pushSnapshot() {
        guard link.isLinked else { return }
        let head = queue.first
        let snapshot = Snapshot(
            total: sessions.count,
            running: sessions.count,
            waiting: queue.count,
            msg: head.map { "approve: \($0.prompt.tool)" } ?? "idle",
            entries: entries,
            prompt: head?.prompt,
            // The rest of the queue by name, not just as a count. Each one belongs to a
            // session the phone already draws, so several at once is several crabs asking
            // rather than a number nobody can act on.
            prompts: queue.dropFirst().map(\.prompt),
            now: Self.now(),
            sessions: sessions
                .map { id, session in
                    SessionSummary(
                        id: id,
                        cwd: session.cwd,
                        started: session.started,
                        active: session.active,
                        decided: session.decided,
                        tokens: session.tokens,
                        finished: session.finished,
                        task: session.task)
                }
                // Stable order, so the list on the phone does not reshuffle every keepalive.
                .sorted { $0.started < $1.started },
            tokens: sessions.values.reduce(0) { $0 + $1.tokens },
            tokensToday: tokensToday(),
            resolved: resolved)
        guard let data = try? LineCodec.encode(snapshot) else { return }
        link.send(data)
    }

    private static func newRequestID() -> String {
        "req_" + UUID().uuidString.prefix(8).lowercased()
    }

    private static let entryLimit = 12

    // MARK: - Test seams

    /// Queue depth, for tests. The same number the phone sees as `waiting`.
    var queueDepth: Int { queue.count }

    /// Identifier of the request currently on screen, for tests.
    var headID: String? { queue.first?.id }
}
