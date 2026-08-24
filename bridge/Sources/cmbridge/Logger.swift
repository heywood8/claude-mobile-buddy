import Foundation

/// Everything goes to stderr so that stdout stays clean for `print-hook`.
struct Logger: Sendable {
    private static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    private static let handle = FileHandle.standardError
    private static let lock = NSLock()

    func info(_ message: @autoclosure () -> String) { write("·", message()) }
    func error(_ message: @autoclosure () -> String) { write("!", message()) }

    /// Every approval decision is journalled, including the ones nobody answered.
    /// A one-sided record is useless when the two sides disagree.
    func decision(_ message: @autoclosure () -> String) { write("→", message()) }

    private func write(_ marker: String, _ message: String) {
        let line = "\(Self.stamp.string(from: Date())) \(marker) \(message)\n"
        Self.lock.lock()
        Self.handle.write(Data(line.utf8))
        Self.lock.unlock()
    }
}

/// `10:42 git push` — the format the phone renders verbatim, formatted here because the
/// host owns the clock and the timezone.
func entryLine(tool: String, hint: String, now: Date = Date()) -> String {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    let short = hint.count > 48 ? String(hint.prefix(48)) + "…" : hint
    return "\(f.string(from: now)) \(tool.lowercased()) \(short)".trimmingCharacters(in: .whitespaces)
}
