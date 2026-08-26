import Foundation

/// Nordic UART Service, as specified in docs/PROTOCOL.md.
enum NUS {
    static let service = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    /// Central writes here.
    static let rx = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    /// Peripheral notifies here.
    static let tx = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
}

/// Head of the approval queue, rendered on the phone.
struct Prompt: Codable, Equatable {
    let id: String
    let tool: String
    /// Truncated by the bridge; see `Prompt.hintLimit`.
    let hint: String
    let cwd: String
    /// Wall-clock second at which the bridge gives up and fails open, so the phone
    /// can show a countdown instead of an indefinite spinner.
    let expires: Int

    static let hintLimit = 512

    static func truncatingHint(id: String, tool: String, hint: String, cwd: String, expires: Int) -> Prompt {
        var h = hint
        if h.utf8.count > hintLimit {
            h = String(decoding: Array(h.utf8.prefix(hintLimit)), as: UTF8.self) + "…"
        }
        return Prompt(id: id, tool: tool, hint: h, cwd: cwd, expires: expires)
    }
}

/// One live Claude Code session.
///
/// Times are absolute seconds in the host's clock, and the snapshot carries the host's `now`
/// alongside them. The phone subtracts one from the other rather than comparing against its
/// own clock, which is off by a second or two and would quietly misreport every duration.
struct SessionSummary: Codable, Equatable {
    let id: String
    /// Where it is working, which is what tells two sessions apart at a glance.
    let cwd: String
    let started: Int
    /// Last tool call seen from it.
    let active: Int
    /// Last time you decided something for it. Zero if you never have.
    let decided: Int
    /// Tokens the model has processed for this session. Zero when the transcript could not
    /// be read, which is not distinguishable from a session that has used none — and does
    /// not need to be, for a number on a dashboard.
    var tokens: Int = 0
    /// When it last finished answering, in the host's clock. Zero while it is working.
    ///
    /// Nothing reports that you have read the answer, so this is the closest thing available:
    /// the moment it stopped talking. How long that counts as "unread" is the phone's guess.
    var finished: Int = 0
    /// The last thing you asked this session for, trimmed to a glance. Empty until it has
    /// heard one — a session resumed mid-flight has been given nothing yet as far as the
    /// bridge knows, and inventing something would be worse than a blank line.
    var task: String = ""

    /// A prompt is a paragraph and this is a phone. One line of it is the reminder; the rest
    /// is in the terminal where you wrote it.
    static let taskLimit = 120

    static func trimTask(_ text: String) -> String {
        let oneLine = text
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard oneLine.count > taskLimit else { return oneLine }
        return String(oneLine.prefix(taskLimit)) + "…"
    }
}

/// A decision that was made somewhere other than the phone.
///
/// There is no hook event for "the user answered the prompt in the terminal" — the flow is
/// `PreToolUse` → `PermissionRequest` → the person types something → `PostToolUse`, and nothing
/// in between reaches us. So a tool that ran is the proof that it was allowed, and the phone is
/// told about it so the crab can react to what you did over there.
struct Resolution: Codable, Equatable {
    let id: String
    /// Whose request it was, so the right session's pet is the one that reacts.
    let session: String
    /// `allowed`, `denied`, or `gone` when the request went away without saying which.
    let how: String
    /// Host clock, to be compared against the snapshot's own `now`.
    let at: Int
}

/// Complete state, not a delta. Sent on change and as a keepalive.
struct Snapshot: Codable, Equatable {
    var t: String = "snap"
    var total: Int
    var running: Int
    var waiting: Int
    var msg: String
    var entries: [String]
    var prompt: Prompt?
    /// The host's clock at the moment this was built.
    var now: Int = 0
    /// Defaulted so a snapshot written before this field existed still decodes.
    var sessions: [SessionSummary] = []
    /// Tokens across every session the bridge knows about, and since local midnight.
    /// Field names follow the maker specification.
    var tokens: Int = 0
    var tokensToday: Int = 0
    /// The last decision taken anywhere but here, so the phone can show that it happened.
    var resolved: Resolution?

    static let keepalive: TimeInterval = 10

    enum CodingKeys: String, CodingKey {
        case t, total, running, waiting, msg, entries, prompt, now, sessions, tokens, resolved
        case tokensToday = "tokens_today"
    }
}

/// The phone's answer. Field names follow Anthropic's maker specification.
struct Decision: Codable, Equatable {
    enum Verdict: String, Codable {
        case once
        case deny
    }

    var cmd: String = "permission"
    let id: String
    let decision: Verdict
}

struct Bye: Codable, Equatable {
    var t: String = "bye"
    let reason: String
}

/// Anything the phone can send us.
enum Inbound: Equatable {
    case decision(Decision)
    case bye(String)
}

enum WireError: Error, Equatable {
    case oversizeLine(Int)
    case unrecognised(String)
}

enum LineCodec {
    /// Decoded lines are capped; a longer one is a protocol violation, not something to buffer.
    static let maxLine = 8 * 1024

    /// A complete wire line, newline included.
    static func encode<T: Encodable>(_ value: T) throws -> Data {
        var data = try payload(value)
        data.append(0x0A)
        return data
    }

    /// The JSON on its own, with no trailing newline.
    ///
    /// This is what goes inside an encrypted frame: the newline is framing for the outer
    /// stream, and the frame envelope is already a line of its own. Sealing it as well would
    /// encrypt a byte that means nothing to the reader.
    static func payload<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return try encoder.encode(value)
    }

    static func decode(_ line: Data) throws -> Inbound {
        guard line.count <= maxLine else { throw WireError.oversizeLine(line.count) }
        let decoder = JSONDecoder()
        if let d = try? decoder.decode(Decision.self, from: line), d.cmd == "permission" {
            return .decision(d)
        }
        if let b = try? decoder.decode(Bye.self, from: line), b.t == "bye" {
            return .bye(b.reason)
        }
        throw WireError.unrecognised(String(decoding: line.prefix(120), as: UTF8.self))
    }
}

/// Reassembles newline-delimited lines out of BLE notification fragments, which arrive
/// chopped at whatever the negotiated MTU allows.
struct LineAssembler {
    private var buffer = Data()

    mutating func feed(_ chunk: Data) -> [Data] {
        buffer.append(chunk)
        var lines: [Data] = []
        while let nl = buffer.firstIndex(of: 0x0A) {
            let line = buffer[buffer.startIndex..<nl]
            buffer = buffer[buffer.index(after: nl)...]
            if !line.isEmpty { lines.append(Data(line)) }
        }
        // A sender that never terminates a line must not be able to exhaust our memory.
        if buffer.count > LineCodec.maxLine { buffer.removeAll(keepingCapacity: false) }
        return lines
    }
}
