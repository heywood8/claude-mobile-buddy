import Foundation

/// Holds the state the phone renders and arbitrates a single approval at a time.
///
/// The walking skeleton keeps one request in flight; a second arriving while the first is
/// on screen fails open immediately rather than queueing. The FIFO queue and the `waiting`
/// counter it feeds are the next milestone.
actor Coordinator {
    /// How long a request may wait for the phone before the terminal takes over.
    /// Deliberately far below Claude Code's ten-minute hook timeout: an unanswered phone
    /// should cost seconds, not a session you come back to find wedged.
    static let window: TimeInterval = 45

    private let link: BLELink
    private let log: Logger

    private var pending: (id: String, continuation: CheckedContinuation<Decision.Verdict?, Never>)?
    private var current: Prompt?
    private var entries: [String] = []
    private var sessions = Set<String>()

    init(link: BLELink, log: Logger) {
        self.link = link
        self.log = log
    }

    func sessionStarted(_ id: String) { sessions.insert(id); pushSnapshot() }
    func sessionEnded(_ id: String) { sessions.remove(id); pushSnapshot() }

    func recordToolUse(tool: String, hint: String) {
        entries.insert(entryLine(tool: tool, hint: hint), at: 0)
        if entries.count > 12 { entries.removeLast(entries.count - 12) }
        pushSnapshot()
    }

    func decide(_ request: HookRequest) async -> HookResponse {
        guard link.isLinked else {
            log.decision("\(request.toolName): no phone linked, deferring to terminal")
            return .noDecision
        }
        guard pending == nil else {
            log.decision("\(request.toolName): phone busy with another request, deferring")
            return .noDecision
        }

        let id = "req_" + UUID().uuidString.prefix(8).lowercased()
        let deadline = Date().addingTimeInterval(Self.window)
        current = Prompt.truncatingHint(
            id: id,
            tool: request.toolName,
            hint: request.hint,
            cwd: request.cwd,
            expires: Int(deadline.timeIntervalSince1970))
        pushSnapshot()
        log.decision("\(id) \(request.toolName) asked — \(request.hint)")

        let verdict = await withCheckedContinuation { (continuation: CheckedContinuation<Decision.Verdict?, Never>) in
            pending = (id, continuation)
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(Self.window * 1_000_000_000))
                await self?.expire(id)
            }
        }

        current = nil
        pushSnapshot()

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

    func resolve(_ decision: Decision) {
        // A decision that lost the race with the timeout, or answers a prompt we already
        // dropped, is not an error — it is just late.
        guard let pending, pending.id == decision.id else { return }
        self.pending = nil
        pending.continuation.resume(returning: decision.decision)
    }

    func linkChanged(_ up: Bool) {
        log.info(up ? "phone linked" : "phone unlinked")
        if up { pushSnapshot() }
    }

    func keepalive() { if link.isLinked { pushSnapshot() } }

    private func expire(_ id: String) {
        guard let pending, pending.id == id else { return }
        self.pending = nil
        pending.continuation.resume(returning: nil)
    }

    private func pushSnapshot() {
        guard link.isLinked else { return }
        let snapshot = Snapshot(
            total: sessions.count,
            running: sessions.count,
            waiting: current == nil ? 0 : 1,
            msg: current.map { "approve: \($0.tool)" } ?? "idle",
            entries: entries,
            prompt: current)
        guard let data = try? LineCodec.encode(snapshot) else { return }
        link.send(data)
    }
}
