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

    private struct Pending {
        let id: String
        let prompt: Prompt
        let continuation: CheckedContinuation<Decision.Verdict?, Never>
    }

    private let link: any LinkSink
    private let log: Logger

    private var queue: [Pending] = []
    private var entries: [String] = []
    private var sessions = Set<String>()

    init(link: any LinkSink, log: Logger, window: TimeInterval = Coordinator.defaultWindow) {
        self.link = link
        self.log = log
        self.window = window
    }

    // MARK: - Session bookkeeping

    func sessionStarted(_ id: String) {
        sessions.insert(id)
        pushSnapshot()
    }

    func sessionEnded(_ id: String) {
        sessions.remove(id)
        pushSnapshot()
    }

    func recordToolUse(tool: String, hint: String) {
        entries.insert(entryLine(tool: tool, hint: hint), at: 0)
        if entries.count > Self.entryLimit { entries.removeLast(entries.count - Self.entryLimit) }
        pushSnapshot()
    }

    // MARK: - Approvals

    func decide(_ request: HookRequest) async -> HookResponse {
        guard link.isLinked else {
            log.decision("\(request.toolName): no phone linked, deferring to terminal")
            return .noDecision
        }

        let id = Self.newRequestID()
        let deadline = Date().addingTimeInterval(window)
        let prompt = Prompt.truncatingHint(
            id: id,
            tool: request.toolName,
            hint: request.hint,
            cwd: request.cwd,
            expires: Int(deadline.timeIntervalSince1970))

        log.decision("\(id) \(request.toolName) asked — \(request.hint)")

        let verdict = await withCheckedContinuation { (continuation: CheckedContinuation<Decision.Verdict?, Never>) in
            queue.append(Pending(id: id, prompt: prompt, continuation: continuation))
            pushSnapshot()
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(self?.window ?? Coordinator.defaultWindow) * 1_000_000_000)
                await self?.expire(id)
            }
        }

        switch verdict {
        case .once:
            log.decision("\(id) allowed from phone")
            return .allow
        case .deny:
            log.decision("\(id) denied from phone")
            return .deny("Denied from phone")
        case nil:
            log.decision("\(id) expired unanswered, deferring to terminal")
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
        pending.continuation.resume(returning: decision.decision)
        pushSnapshot()
    }

    func linkChanged(_ up: Bool) {
        log.info(up ? "phone linked" : "phone unlinked")
        if up {
            pushSnapshot()
            return
        }
        // With the link gone nobody is going to answer. Releasing the whole queue at once
        // beats leaving several terminals blocked for the rest of their windows.
        let stranded = queue
        queue.removeAll()
        for pending in stranded {
            log.decision("\(pending.id) released, phone went away")
            pending.continuation.resume(returning: nil)
        }
    }

    func keepalive() {
        if link.isLinked { pushSnapshot() }
    }

    // MARK: - Internals

    private func expire(_ id: String) {
        guard let index = queue.firstIndex(where: { $0.id == id }) else { return }
        let pending = queue.remove(at: index)
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
            prompt: head?.prompt)
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
