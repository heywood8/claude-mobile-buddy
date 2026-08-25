import Foundation

/// A JSON tree that remembers the order its keys were written in.
///
/// `JSONSerialization` reads objects into dictionaries, which are unordered, so writing a file
/// back out reshuffles every key in it. For a configuration file someone maintains by hand
/// that turns a three-line change into a diff of the whole file — and a diff nobody reads is
/// not a confirmation, it is a formality.
///
/// Numbers keep their original text rather than becoming Doubles, so `1860` does not come back
/// as `1860.0` and a version like `1.10` does not lose its zero.
indirect enum JSONValue: Equatable {
    case object([(key: String, value: JSONValue)])
    case array([JSONValue])
    case string(String)
    case number(String)
    case bool(Bool)
    case null

    static func == (lhs: JSONValue, rhs: JSONValue) -> Bool {
        switch (lhs, rhs) {
        case (.object(let a), .object(let b)):
            return a.count == b.count
                && zip(a, b).allSatisfy { $0.key == $1.key && $0.value == $1.value }
        case (.array(let a), .array(let b)): return a == b
        case (.string(let a), .string(let b)): return a == b
        case (.number(let a), .number(let b)): return a == b
        case (.bool(let a), .bool(let b)): return a == b
        case (.null, .null): return true
        default: return false
        }
    }

    // MARK: - Access

    subscript(key: String) -> JSONValue? {
        get {
            guard case .object(let pairs) = self else { return nil }
            return pairs.first { $0.key == key }?.value
        }
        set {
            guard case .object(var pairs) = self else { return }
            if let index = pairs.firstIndex(where: { $0.key == key }) {
                if let newValue { pairs[index].value = newValue } else { pairs.remove(at: index) }
            } else if let newValue {
                pairs.append((key: key, value: newValue))
            }
            self = .object(pairs)
        }
    }

    var arrayValue: [JSONValue]? {
        guard case .array(let items) = self else { return nil }
        return items
    }

    var stringValue: String? {
        guard case .string(let text) = self else { return nil }
        return text
    }
}

enum JSONParseError: Error, Equatable {
    case unexpected(String, at: Int)
    case truncated
}

extension JSONValue {
    static func parse(_ text: String) throws -> JSONValue {
        var parser = JSONParser(Array(text.unicodeScalars))
        let value = try parser.parseValue()
        parser.skipWhitespace()
        guard parser.atEnd else { throw JSONParseError.unexpected("trailing content", at: parser.index) }
        return value
    }

    /// Two-space indentation, which is what Claude Code's own settings file uses.
    func serialized(indent: Int = 0) -> String {
        let pad = String(repeating: " ", count: indent)
        let inner = String(repeating: " ", count: indent + 2)
        switch self {
        case .object(let pairs):
            guard !pairs.isEmpty else { return "{}" }
            let body = pairs.map { pair in
                "\(inner)\(JSONValue.quote(pair.key)): \(pair.value.serialized(indent: indent + 2))"
            }.joined(separator: ",\n")
            return "{\n\(body)\n\(pad)}"
        case .array(let items):
            guard !items.isEmpty else { return "[]" }
            let body = items.map { "\(inner)\($0.serialized(indent: indent + 2))" }
                .joined(separator: ",\n")
            return "[\n\(body)\n\(pad)]"
        case .string(let text): return JSONValue.quote(text)
        case .number(let raw): return raw
        case .bool(let flag): return flag ? "true" : "false"
        case .null: return "null"
        }
    }

    static func quote(_ text: String) -> String {
        var out = "\""
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }
}

private struct JSONParser {
    let scalars: [Unicode.Scalar]
    var index = 0

    init(_ scalars: [Unicode.Scalar]) { self.scalars = scalars }

    var atEnd: Bool { index >= scalars.count }

    mutating func skipWhitespace() {
        while index < scalars.count, scalars[index] == " " || scalars[index] == "\n"
            || scalars[index] == "\t" || scalars[index] == "\r" {
            index += 1
        }
    }

    mutating func parseValue() throws -> JSONValue {
        skipWhitespace()
        guard index < scalars.count else { throw JSONParseError.truncated }
        switch scalars[index] {
        case "{": return try parseObject()
        case "[": return try parseArray()
        case "\"": return .string(try parseString())
        case "t": try expect("true"); return .bool(true)
        case "f": try expect("false"); return .bool(false)
        case "n": try expect("null"); return .null
        default: return .number(try parseNumber())
        }
    }

    mutating func parseObject() throws -> JSONValue {
        index += 1
        var pairs: [(key: String, value: JSONValue)] = []
        skipWhitespace()
        if index < scalars.count, scalars[index] == "}" { index += 1; return .object(pairs) }
        while true {
            skipWhitespace()
            let key = try parseString()
            skipWhitespace()
            guard index < scalars.count, scalars[index] == ":" else {
                throw JSONParseError.unexpected("expected :", at: index)
            }
            index += 1
            pairs.append((key: key, value: try parseValue()))
            skipWhitespace()
            guard index < scalars.count else { throw JSONParseError.truncated }
            if scalars[index] == "," { index += 1; continue }
            if scalars[index] == "}" { index += 1; return .object(pairs) }
            throw JSONParseError.unexpected("expected , or }", at: index)
        }
    }

    mutating func parseArray() throws -> JSONValue {
        index += 1
        var items: [JSONValue] = []
        skipWhitespace()
        if index < scalars.count, scalars[index] == "]" { index += 1; return .array(items) }
        while true {
            items.append(try parseValue())
            skipWhitespace()
            guard index < scalars.count else { throw JSONParseError.truncated }
            if scalars[index] == "," { index += 1; continue }
            if scalars[index] == "]" { index += 1; return .array(items) }
            throw JSONParseError.unexpected("expected , or ]", at: index)
        }
    }

    mutating func parseString() throws -> String {
        guard index < scalars.count, scalars[index] == "\"" else {
            throw JSONParseError.unexpected("expected a string", at: index)
        }
        index += 1
        var out = String.UnicodeScalarView()
        while index < scalars.count {
            let scalar = scalars[index]
            index += 1
            if scalar == "\"" { return String(out) }
            guard scalar == "\\" else { out.append(scalar); continue }
            guard index < scalars.count else { throw JSONParseError.truncated }
            let escape = scalars[index]
            index += 1
            switch escape {
            case "\"": out.append("\"")
            case "\\": out.append("\\")
            case "/": out.append("/")
            case "n": out.append("\n")
            case "r": out.append("\r")
            case "t": out.append("\t")
            case "b": out.append(Unicode.Scalar(8))
            case "f": out.append(Unicode.Scalar(12))
            case "u":
                guard index + 4 <= scalars.count else { throw JSONParseError.truncated }
                let hex = String(String.UnicodeScalarView(scalars[index..<(index + 4)]))
                index += 4
                guard let code = UInt32(hex, radix: 16), let scalar = Unicode.Scalar(code) else {
                    throw JSONParseError.unexpected("bad \\u escape", at: index)
                }
                out.append(scalar)
            default:
                throw JSONParseError.unexpected("bad escape", at: index)
            }
        }
        throw JSONParseError.truncated
    }

    mutating func parseNumber() throws -> String {
        let start = index
        while index < scalars.count,
              "0123456789+-.eE".unicodeScalars.contains(scalars[index]) {
            index += 1
        }
        guard index > start else { throw JSONParseError.unexpected("expected a value", at: index) }
        return String(String.UnicodeScalarView(scalars[start..<index]))
    }

    mutating func expect(_ literal: String) throws {
        for scalar in literal.unicodeScalars {
            guard index < scalars.count, scalars[index] == scalar else {
                throw JSONParseError.unexpected("expected \(literal)", at: index)
            }
            index += 1
        }
    }
}
