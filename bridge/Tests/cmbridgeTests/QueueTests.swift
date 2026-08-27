import Testing
import Foundation
@testable import cmbridge

/// A link that records what would have gone to the phone, so the queue can be exercised
/// without a radio.
final class FakeLink: LinkSink, @unchecked Sendable {
    private let lock = NSLock()
    private var _isLinked: Bool
    private var _lines: [Data] = []

    init(isLinked: Bool = true) {
        _isLinked = isLinked
    }

    var isLinked: Bool {
        lock.lock(); defer { lock.unlock() }
        return _isLinked
    }

    func setLinked(_ value: Bool) {
        lock.lock(); _isLinked = value; lock.unlock()
    }

    func send(_ line: Data) {
        lock.lock(); _lines.append(line); lock.unlock()
    }

    /// The most recent snapshot pushed to the phone.
    var lastSnapshot: Snapshot? {
        lock.lock(); defer { lock.unlock() }
        guard let line = _lines.last else { return nil }
        return try? JSONDecoder().decode(Snapshot.self, from: line)
    }

    var snapshotCount: Int {
        lock.lock(); defer { lock.unlock() }
        return _lines.count
    }
}

@Suite("Approval queue")
struct QueueTests {

    @Test("defers to the terminal when no phone is linked")
    func defersWithoutLink() async {
        let link = FakeLink(isLinked: false)
        let coordinator = Coordinator(link: link, log: Logger())

        let response = await coordinator.decide(request(tool: "Bash", hint: "git push"))

        #expect(response.isNoDecision)
        #expect(link.snapshotCount == 0)
    }

    @Test("leaves a question that cannot be answered remotely in the terminal")
    func skipsUnanswerableTools() async {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        // Approving this would not answer anything, and while it sat on the phone's one
        // screen it would hide approvals that could actually be given.
        let response = await coordinator.decide(request(tool: "AskUserQuestion", hint: "which?"))

        #expect(response.isNoDecision)
        #expect(link.snapshotCount == 0)
    }

    @Test("shows the first request and counts the rest")
    func queuesInArrivalOrder() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        // Three requests arrive while nobody is answering. The phone renders one at a time.
        let first = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }
        let second = Task { await coordinator.decide(request(tool: "Write", hint: "two")) }
        try await settle { await coordinator.queueDepth == 2 }
        let third = Task { await coordinator.decide(request(tool: "Edit", hint: "three")) }
        try await settle { await coordinator.queueDepth == 3 }

        let snapshot = try #require(link.lastSnapshot)
        #expect(snapshot.waiting == 3)
        #expect(snapshot.prompt?.tool == "Bash")
        #expect(snapshot.prompt?.hint == "one")
        #expect(snapshot.msg == "approve: Bash")

        // Answering the head promotes the next one.
        let headID = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: headID, decision: .once))
        #expect(await first.value.isAllow)

        let promoted = try #require(link.lastSnapshot)
        #expect(promoted.waiting == 2)
        #expect(promoted.prompt?.tool == "Write")

        for task in [second, third] {
            let id = try #require(await coordinator.headID)
            await coordinator.resolve(Decision(id: id, decision: .deny))
            #expect(await task.value.isDeny)
        }

        #expect(await coordinator.queueDepth == 0)
        #expect(link.lastSnapshot?.prompt == nil)
        #expect(link.lastSnapshot?.msg == "idle")
    }

    @Test("answers a request that has already been overtaken")
    func resolvesOutOfOrder() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        let first = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }
        let firstID = try #require(await coordinator.headID)
        let second = Task { await coordinator.decide(request(tool: "Write", hint: "two")) }
        try await settle { await coordinator.queueDepth == 2 }

        // A decision for something no longer on screen is a race, not an error.
        await coordinator.resolve(Decision(id: firstID, decision: .once))
        #expect(await first.value.isAllow)

        let remaining = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: remaining, decision: .once))
        #expect(await second.value.isAllow)
    }

    @Test("ignores a decision for an unknown request")
    func ignoresUnknownDecision() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        let pending = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }

        await coordinator.resolve(Decision(id: "req_nothing", decision: .once))
        #expect(await coordinator.queueDepth == 1)

        let id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        #expect(await pending.value.isAllow)
    }

    @Test("releases everything queued when the phone stays away")
    func releasesOnUnlink() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger(), linkGrace: 0.05)

        let first = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        let second = Task { await coordinator.decide(request(tool: "Write", hint: "two")) }
        try await settle { await coordinator.queueDepth == 2 }

        // Nobody is going to answer these. Leaving them blocked for the rest of their windows
        // would strand two terminals for no reason.
        link.setLinked(false)
        await coordinator.linkChanged(false)

        #expect(await first.value.isNoDecision)
        #expect(await second.value.isNoDecision)
        #expect(await coordinator.queueDepth == 0)
    }

    @Test("keeps the queue through a link that comes straight back")
    func survivesAFlicker() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger(), linkGrace: 0.4)

        let pending = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }

        // What a reinstall of the phone app looks like from here: gone, back four seconds
        // later. Dumping the queue at the first flicker hands the decision to a terminal
        // nobody is watching.
        link.setLinked(false)
        await coordinator.linkChanged(false)
        link.setLinked(true)
        await coordinator.linkChanged(true)

        try await Task.sleep(nanoseconds: 600_000_000)
        #expect(await coordinator.queueDepth == 1)

        let id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        #expect(await pending.value.isAllow)
    }

    @Test("withdraws a request whose caller has gone away")
    func withdrawsOnCancel() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        let abandoned = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }
        #expect(link.lastSnapshot?.prompt != nil)

        // Claude Code closing the socket — an interrupted tool call — is the only signal the
        // bridge gets that nobody wants the answer. Without acting on it the phone keeps
        // showing a decision that can no longer do anything.
        abandoned.cancel()

        try await settle { await coordinator.queueDepth == 0 }
        #expect(link.lastSnapshot?.prompt == nil)
        #expect(link.lastSnapshot?.waiting == 0)
    }

    @Test("withdrawing one request promotes the next")
    func withdrawPromotesTheNext() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        let abandoned = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }
        let waiting = Task { await coordinator.decide(request(tool: "Write", hint: "two")) }
        try await settle { await coordinator.queueDepth == 2 }

        abandoned.cancel()
        try await settle { await coordinator.queueDepth == 1 }
        #expect(link.lastSnapshot?.prompt?.tool == "Write")

        let id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        #expect(await waiting.value.isAllow)
    }

    @Test("carries the deadline so the phone can count down")
    func carriesDeadline() async throws {
        let link = FakeLink()
        let window: TimeInterval = 90
        let coordinator = Coordinator(link: link, log: Logger(), window: window)

        let before = Int(Date().timeIntervalSince1970)
        let pending = Task { await coordinator.decide(request(tool: "Bash", hint: "one")) }
        try await settle { await coordinator.queueDepth == 1 }

        let expires = try #require(link.lastSnapshot?.prompt?.expires)
        #expect(expires >= before + Int(window) - 1)
        #expect(expires <= before + Int(window) + 1)

        let id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        _ = await pending.value
    }

    @Test("truncates an overlong hint rather than sending it whole")
    func truncatesHint() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        let long = String(repeating: "x", count: Prompt.hintLimit * 2)
        let pending = Task { await coordinator.decide(request(tool: "Bash", hint: long)) }
        try await settle { await coordinator.queueDepth == 1 }

        let hint = try #require(link.lastSnapshot?.prompt?.hint)
        #expect(hint.utf8.count <= Prompt.hintLimit + 4)

        let id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        _ = await pending.value
    }

    @Test("carries the tool's own reason, trimmed, and leaves it empty when there is none")
    func carriesWhy() async throws {
        let link = FakeLink()
        let coordinator = Coordinator(link: link, log: Logger())

        let long = String(repeating: "y", count: Prompt.whyLimit * 2)
        let pending = Task {
            await coordinator.decide(request(tool: "Bash", hint: "ss -lntp", why: long))
        }
        try await settle { await coordinator.queueDepth == 1 }

        let why = try #require(link.lastSnapshot?.prompt?.why)
        #expect(why.hasPrefix("yyy"))
        #expect(why.utf8.count <= Prompt.whyLimit + 4)

        var id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        _ = await pending.value

        // Most tools carry no description at all, and an empty string is the honest answer —
        // the phone leaves the line off rather than inventing a reason.
        let bare = Task { await coordinator.decide(request(tool: "Write", hint: "a.txt")) }
        try await settle { await coordinator.queueDepth == 1 }
        #expect(link.lastSnapshot?.prompt?.why == "")

        id = try #require(await coordinator.headID)
        await coordinator.resolve(Decision(id: id, decision: .once))
        _ = await bare.value
    }

    // MARK: - Helpers

    private func request(tool: String, hint: String, why: String = "") -> HookRequest {
        var input: [String: Any] = ["command": hint]
        if !why.isEmpty { input["description"] = why }
        let body: [String: Any] = [
            "session_id": "test",
            "cwd": "/tmp/project",
            "hook_event_name": "PermissionRequest",
            "tool_name": tool,
            "tool_input": input,
        ]
        let data = try! JSONSerialization.data(withJSONObject: body)
        return HookRequest(body: data)!
    }

    /// Waits for a condition the coordinator reaches on its own actor, rather than sleeping a
    /// fixed amount and hoping.
    private func settle(
        _ condition: @Sendable () async -> Bool,
        within: TimeInterval = 2
    ) async throws {
        let deadline = Date().addingTimeInterval(within)
        while Date() < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        Issue.record("condition never became true")
    }
}

private extension HookResponse {
    var isAllow: Bool { if case .allow = self { return true } else { return false } }
    var isDeny: Bool { if case .deny = self { return true } else { return false } }
    var isNoDecision: Bool { if case .noDecision = self { return true } else { return false } }
}
