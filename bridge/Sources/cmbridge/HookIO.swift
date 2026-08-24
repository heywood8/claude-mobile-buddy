import Foundation

/// A `PermissionRequest` event from Claude Code.
///
/// The event's exact field set is not pinned down here on purpose. We read the few things we
/// need and keep the raw body for logging, so an unfamiliar payload degrades into a vaguer
/// prompt on the phone rather than a failure.
struct HookRequest {
    let raw: [String: Any]
    let sessionID: String
    let toolName: String
    let cwd: String
    /// Best-effort one-line summary of what the tool is about to do.
    let hint: String

    init?(body: Data) {
        guard let obj = try? JSONSerialization.jsonObject(with: body) as? [String: Any] else {
            return nil
        }
        raw = obj
        sessionID = (obj["session_id"] as? String) ?? "unknown"
        toolName = (obj["tool_name"] as? String) ?? "unknown"
        cwd = Self.abbreviateHome((obj["cwd"] as? String) ?? "")
        hint = Self.summarise(tool: toolName, input: obj["tool_input"] as? [String: Any])
    }

    private static func abbreviateHome(_ path: String) -> String {
        let home = FileManager.default.homeDirectoryForCurrentUser.path
        guard !home.isEmpty, path.hasPrefix(home) else { return path }
        return "~" + path.dropFirst(home.count)
    }

    private static func summarise(tool: String, input: [String: Any]?) -> String {
        guard let input else { return "" }
        // The fields worth showing differ per tool, and showing the wrong one is worse than
        // showing a compact dump.
        for key in ["command", "file_path", "path", "pattern", "url", "prompt"] {
            if let value = input[key] as? String, !value.isEmpty { return value }
        }
        if let data = try? JSONSerialization.data(withJSONObject: input, options: [.sortedKeys]) {
            return String(decoding: data, as: UTF8.self)
        }
        return ""
    }
}

/// What we hand back to Claude Code in the HTTP response body.
enum HookResponse {
    case allow
    case deny(String)
    /// No opinion — Claude Code falls back to its own terminal prompt.
    case noDecision

    var jsonData: Data {
        switch self {
        case .noDecision:
            return Data("{}".utf8)
        case .allow:
            return Self.wrap(["behavior": "allow"])
        case .deny(let message):
            return Self.wrap(["behavior": "deny", "message": message])
        }
    }

    private static func wrap(_ decision: [String: String]) -> Data {
        let body: [String: Any] = [
            "hookSpecificOutput": [
                "hookEventName": "PermissionRequest",
                "decision": decision,
            ],
        ]
        return (try? JSONSerialization.data(withJSONObject: body, options: [.sortedKeys]))
            ?? Data("{}".utf8)
    }
}
