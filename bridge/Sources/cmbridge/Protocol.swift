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

/// Complete state, not a delta. Sent on change and as a keepalive.
struct Snapshot: Codable, Equatable {
    var t: String = "snap"
    var total: Int
    var running: Int
    var waiting: Int
    var msg: String
    var entries: [String]
    var prompt: Prompt?

    static let keepalive: TimeInterval = 10
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

    static func encode<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        var data = try encoder.encode(value)
        data.append(0x0A)
        return data
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
