import Foundation

/// Reads token usage out of a Claude Code transcript, incrementally.
///
/// The transcript is append-only JSONL and grows to megabytes over a working session, so
/// re-reading it on every hook would mean parsing the whole thing per tool call. Each file is
/// remembered by the byte offset already accounted for, and only what arrived since is parsed.
///
/// Nothing here is load-bearing, deliberately. The transcript format belongs to Claude Code
/// and carries no stability guarantee, so a line that will not parse, a field that moved, a
/// file that is not there — all of it comes back as zero rather than as an error. A number
/// missing from a dashboard is a fair price for reading someone else's file.
struct TranscriptReader {
    /// Bytes already counted, per transcript path.
    private var consumed: [String: UInt64] = [:]

    /// Tokens that have appeared in this file since the last look.
    mutating func delta(path: String) -> Int {
        guard !path.isEmpty, let handle = FileHandle(forReadingAtPath: path) else { return 0 }
        defer { try? handle.close() }

        let size = (try? handle.seekToEnd()) ?? 0
        var start = consumed[path] ?? 0
        // A file that shrank is not the file we were reading: the session was resumed into a
        // new transcript at the same path, or it was rewritten. Reading on from the old offset
        // would land in the middle of a line and count nothing for the rest of the session.
        if start > size { start = 0 }
        guard size > start else {
            consumed[path] = size
            return 0
        }

        try? handle.seek(toOffset: start)
        guard let data = try? handle.readToEnd(), !data.isEmpty else { return 0 }

        // Only whole lines count. The tail of a line still being written is left where it is,
        // to be read again once its newline arrives.
        guard let end = data.lastIndex(of: 0x0A) else { return 0 }
        let complete = data[data.startIndex..<end]
        consumed[path] = start + UInt64(complete.count) + 1

        var total = 0
        for line in complete.split(separator: 0x0A) where !line.isEmpty {
            total += Self.tokens(inLine: Data(line))
        }
        return total
    }

    /// Every field of a usage record added together — what the model processed.
    ///
    /// Not a cost estimate: separating fresh input from cache reads is what a bill does, and
    /// the four numbers are priced differently. This one answers "how much work went through
    /// here", which is the question a phone screen can ask without qualification.
    static func tokens(inLine line: Data) -> Int {
        guard
            let object = try? JSONSerialization.jsonObject(with: line) as? [String: Any],
            let message = object["message"] as? [String: Any],
            let usage = message["usage"] as? [String: Any]
        else { return 0 }
        return fields.reduce(0) { sum, key in sum + ((usage[key] as? Int) ?? 0) }
    }

    private static let fields = [
        "input_tokens",
        "output_tokens",
        "cache_creation_input_tokens",
        "cache_read_input_tokens",
    ]
}
