import Foundation

/// Merges the bridge's hooks into an existing Claude Code settings file.
///
/// Written because pasting them by hand is where configurations break: the file usually
/// already has a `hooks` block, and the entry that has to be *appended* to an existing array
/// looks exactly like the one that can be added wholesale. A program can tell those apart.
///
/// Nothing here writes without being asked. The caller shows the diff and takes the answer.
enum HookInstaller {
    /// One hook: which event, where it posts, and what the phone loses without it.
    struct Hook {
        let event: String
        let path: String
        /// Only the tools worth showing. Without a matcher these fire on every Read and Grep,
        /// which is a lot of noise for a cosmetic feed.
        var matcher: String?
        /// Nil means the standard five seconds.
        var timeout: Int?
        /// Said out loud by `status`, so a missing line in a JSON file is not diagnosed by
        /// staring at an animation that never plays.
        let purpose: String
    }

    /// Every hook the bridge installs, in one place.
    ///
    /// There were two of these lists once — this one and the snippet `print-hook` renders —
    /// and they drifted apart: four events were printed and never installed, while
    /// `install-hook` reported the file was already up to date and rewrote it unchanged. One
    /// list, three readers.
    static func hooks(window: TimeInterval) -> [Hook] {
        let tools = "Bash|Write|Edit|Task"
        return [
            // A minute past the bridge's own window, so the bridge is always the side that
            // decides to give up.
            Hook(event: "PermissionRequest", path: "permission-request", matcher: nil,
                 timeout: Int(window) + 60, purpose: "the approvals themselves"),
            Hook(event: "SessionStart", path: "session-start", matcher: nil, timeout: nil,
                 purpose: "sessions appearing"),
            Hook(event: "SessionEnd", path: "session-end", matcher: nil, timeout: nil,
                 purpose: "sessions leaving"),
            Hook(event: "PostToolUse", path: "tool-use", matcher: tools, timeout: nil,
                 purpose: "recent calls, and clearing a card the terminal answered"),
            Hook(event: "PostToolUseFailure", path: "tool-use", matcher: tools, timeout: nil,
                 purpose: "clearing a card when the command failed"),
            Hook(event: "UserPromptSubmit", path: "prompt", matcher: nil, timeout: nil,
                 purpose: "what each session was asked to do"),
            Hook(event: "PermissionDenied", path: "permission-denied", matcher: nil,
                 timeout: nil, purpose: "denials made by auto mode"),
            Hook(event: "Stop", path: "turn-end", matcher: nil, timeout: nil,
                 purpose: "finished, resting, and clearing a card denied by hand"),
        ]
    }

    /// The block `print-hook` shows, rendered from the same list that installs it.
    static func snippet(port: Int, window: TimeInterval) -> String {
        var events = JSONValue.object([])
        for hook in hooks(window: window) {
            events[hook.event] = .array([entry(hook, port: port)])
        }
        return JSONValue.object([("hooks", events)]).serialized()
    }

    struct Plan {
        let path: URL
        let before: String
        let after: String
        /// True when the file's own formatting differs from ours, so writing reflows it.
        let reformats: Bool

        var isNoop: Bool { before == after }
    }

    static var defaultPath: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".claude/settings.json")
    }

    /// Builds the merged file without touching anything.
    static func plan(path: URL, port: Int, window: TimeInterval) throws -> Plan {
        let original = (try? String(contentsOf: path, encoding: .utf8)) ?? "{}"
        var root = try JSONValue.parse(original)
        guard case .object = root else {
            throw JSONParseError.unexpected("settings.json is not an object", at: 0)
        }

        var hooks = root["hooks"] ?? .object([])
        for hook in Self.hooks(window: window) {
            let existing = hooks[hook.event]?.arrayValue ?? []
            hooks[hook.event] = .array(merge(entry(hook, port: port),
                                             into: existing,
                                             base: base(port: port)))
        }
        root["hooks"] = hooks

        let rendered = root.serialized() + "\n"
        let untouched = (try? JSONValue.parse(original).serialized()) ?? ""
        return Plan(
            path: path,
            before: untouched + "\n",
            after: rendered,
            reformats: untouched + "\n" != original)
    }

    /// Replaces our own entry if one is already there, appends otherwise.
    ///
    /// Ours is the one pointing at this bridge's port. Re-running after changing the window or
    /// the port updates it rather than leaving a second, stale hook behind — which would be
    /// worse than not installing at all, since the old one would still be answering.
    static func merge(_ ours: JSONValue, into existing: [JSONValue], base: String) -> [JSONValue] {
        var result = existing
        if let index = existing.firstIndex(where: { isOurs($0, base: base) }) {
            result[index] = ours
        } else {
            result.append(ours)
        }
        return result
    }

    static func isOurs(_ entry: JSONValue, base: String) -> Bool {
        guard let hooks = entry["hooks"]?.arrayValue else { return false }
        return hooks.contains { ($0["url"]?.stringValue ?? "").hasPrefix(base) }
    }

    static func base(port: Int) -> String { "http://127.0.0.1:\(port)/" }

    static func entry(_ hook: Hook, port: Int) -> JSONValue {
        var fields: [(String, JSONValue)] = []
        if let matcher = hook.matcher { fields.append(("matcher", .string(matcher))) }
        fields.append((
            "hooks",
            .array([handler(url: base(port: port) + hook.path, timeout: hook.timeout ?? 5)])
        ))
        return .object(fields)
    }

    private static func handler(url: String, timeout: Int) -> JSONValue {
        .object([
            ("type", .string("http")),
            ("url", .string(url)),
            ("timeout", .number(String(timeout))),
        ])
    }

    /// Writes the merged file, keeping the previous one alongside it.
    static func apply(_ plan: Plan) throws {
        let backup = plan.path.appendingPathExtension("bak")
        if FileManager.default.fileExists(atPath: plan.path.path) {
            try? FileManager.default.removeItem(at: backup)
            try FileManager.default.copyItem(at: plan.path, to: backup)
        }
        try plan.after.write(to: plan.path, atomically: true, encoding: .utf8)
    }
}

/// A line diff over a longest common subsequence.
///
/// The naive version — trim the common head, trim the common tail, print the rest as removed
/// then added — misreports any insertion that lands in the middle: everything after it fails
/// to line up and gets printed twice, once as a deletion and once as an addition. Untouched
/// configuration then looks rewritten, which is exactly the alarm a confirmation prompt must
/// not raise falsely.
enum LineDiff {
    enum Edit: Equatable {
        case same(String)
        case removed(String)
        case added(String)
    }

    static func edits(before: String, after: String) -> [Edit] {
        let old = before.components(separatedBy: "\n")
        let new = after.components(separatedBy: "\n")

        // Classic LCS table. The files here are a few hundred lines, so the quadratic cost is
        // irrelevant and the correctness is easy to see.
        var lengths = [[Int]](
            repeating: [Int](repeating: 0, count: new.count + 1), count: old.count + 1)
        for i in stride(from: old.count - 1, through: 0, by: -1) {
            for j in stride(from: new.count - 1, through: 0, by: -1) {
                lengths[i][j] = old[i] == new[j]
                    ? lengths[i + 1][j + 1] + 1
                    : max(lengths[i + 1][j], lengths[i][j + 1])
            }
        }

        var result: [Edit] = []
        var i = 0, j = 0
        while i < old.count, j < new.count {
            if old[i] == new[j] {
                result.append(.same(old[i]))
                i += 1
                j += 1
            } else if lengths[i + 1][j] >= lengths[i][j + 1] {
                result.append(.removed(old[i]))
                i += 1
            } else {
                result.append(.added(new[j]))
                j += 1
            }
        }
        while i < old.count { result.append(.removed(old[i])); i += 1 }
        while j < new.count { result.append(.added(new[j])); j += 1 }
        return result
    }

    /// Changed lines with a few lines of context, and a marker where untouched runs are elided.
    static func render(before: String, after: String, context: Int = 3) -> String {
        let script = edits(before: before, after: after)
        let changed = script.indices.filter { if case .same = script[$0] { return false } else { return true } }
        guard let first = changed.first else { return "" }

        var keep = Set<Int>()
        for index in changed {
            for nearby in max(0, index - context)...min(script.count - 1, index + context) {
                keep.insert(nearby)
            }
        }

        var lines: [String] = []
        var previous = first
        for index in keep.sorted() {
            if index > previous + 1 {
                lines.append("  … \(index - previous - 1) unchanged lines")
            }
            switch script[index] {
            case .same(let text): lines.append("  " + text)
            case .removed(let text): lines.append("- " + text)
            case .added(let text): lines.append("+ " + text)
            }
            previous = index
        }
        return lines.joined(separator: "\n")
    }

    /// How many lines the change actually touches, for a one-line summary.
    static func counts(before: String, after: String) -> (added: Int, removed: Int) {
        var added = 0, removed = 0
        for edit in edits(before: before, after: after) {
            if case .added = edit { added += 1 }
            if case .removed = edit { removed += 1 }
        }
        return (added, removed)
    }
}
