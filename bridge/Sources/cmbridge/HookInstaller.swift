import Foundation

/// Merges the bridge's hooks into an existing Claude Code settings file.
///
/// Written because pasting them by hand is where configurations break: the file usually
/// already has a `hooks` block, and the entry that has to be *appended* to an existing array
/// looks exactly like the one that can be added wholesale. A program can tell those apart.
///
/// Nothing here writes without being asked. The caller shows the diff and takes the answer.
enum HookInstaller {
    static let events = ["PermissionRequest", "SessionStart", "SessionEnd", "PostToolUse"]

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
        for event in events {
            let existing = hooks[event]?.arrayValue ?? []
            hooks[event] = .array(merge(entry(for: event, port: port, window: window),
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

    static func entry(for event: String, port: Int, window: TimeInterval) -> JSONValue {
        let base = base(port: port)
        switch event {
        case "PermissionRequest":
            // A minute past the bridge's own window, so the bridge is always the side that
            // decides to give up.
            return .object([
                ("hooks", .array([handler(url: base + "permission-request",
                                          timeout: Int(window) + 60)])),
            ])
        case "SessionStart":
            return .object([("hooks", .array([handler(url: base + "session-start", timeout: 5)]))])
        case "SessionEnd":
            return .object([("hooks", .array([handler(url: base + "session-end", timeout: 5)]))])
        default:
            // Only the tools worth showing in the phone's recent-calls list. Without a matcher
            // this fires on every Read and Grep, which is a lot of noise for a cosmetic feed.
            return .object([
                ("matcher", .string("Bash|Write|Edit|Task")),
                ("hooks", .array([handler(url: base + "tool-use", timeout: 5)])),
            ])
        }
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

/// A diff that shows only what changed, by trimming the identical head and tail.
///
/// Enough for insertions into a settings file, and small enough to read — which is the point:
/// a diff nobody reads is not a confirmation.
enum LineDiff {
    static func render(before: String, after: String, context: Int = 3) -> String {
        let old = before.components(separatedBy: "\n")
        let new = after.components(separatedBy: "\n")

        var head = 0
        while head < old.count, head < new.count, old[head] == new[head] { head += 1 }

        var tail = 0
        while tail < old.count - head, tail < new.count - head,
              old[old.count - 1 - tail] == new[new.count - 1 - tail] { tail += 1 }

        let removed = old[head..<(old.count - tail)]
        let added = new[head..<(new.count - tail)]

        var lines: [String] = []
        for line in old[max(0, head - context)..<head] { lines.append("  " + line) }
        for line in removed { lines.append("- " + line) }
        for line in added { lines.append("+ " + line) }
        let tailStart = old.count - tail
        for line in old[tailStart..<min(old.count, tailStart + context)] { lines.append("  " + line) }
        return lines.joined(separator: "\n")
    }
}
