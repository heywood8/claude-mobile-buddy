import Testing
import Foundation
@testable import cmbridge

@Suite("Transcript reading")
struct TranscriptTests {
    /// One assistant line as Claude Code writes it, trimmed to the parts that are read.
    private func line(input: Int, output: Int, cacheRead: Int = 0) -> String {
        """
        {"type":"assistant","message":{"role":"assistant","usage":{"input_tokens":\(input),\
        "output_tokens":\(output),"cache_creation_input_tokens":0,\
        "cache_read_input_tokens":\(cacheRead)}}}
        """
    }

    private func temporaryFile() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("cmb-transcript-\(UUID().uuidString).jsonl")
    }

    @Test("adds up every field of a usage record")
    func sumsUsage() {
        let data = Data(line(input: 12, output: 34, cacheRead: 1000).utf8)
        #expect(TranscriptReader.tokens(inLine: data) == 1046)
    }

    @Test("ignores lines that carry no usage")
    func ignoresOtherLines() {
        #expect(TranscriptReader.tokens(inLine: Data(#"{"type":"user"}"#.utf8)) == 0)
        #expect(TranscriptReader.tokens(inLine: Data("not json at all".utf8)) == 0)
    }

    @Test("counts each line once, however often it is read")
    func countsAppendsOnly() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        try (line(input: 10, output: 5) + "\n").write(to: url, atomically: true, encoding: .utf8)

        var reader = TranscriptReader()
        #expect(reader.delta(path: url.path) == 15)
        // Nothing was appended, so there is nothing to count — this is what stops a tool call
        // every few seconds from re-counting a transcript that only grows.
        #expect(reader.delta(path: url.path) == 0)

        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((line(input: 1, output: 2) + "\n").utf8))
        try handle.close()

        #expect(reader.delta(path: url.path) == 3)
    }

    @Test("leaves a half-written line for the next pass")
    func waitsForTheNewline() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        let complete = line(input: 10, output: 5) + "\n"
        let partial = String(line(input: 100, output: 100).prefix(40))
        try (complete + partial).write(to: url, atomically: true, encoding: .utf8)

        var reader = TranscriptReader()
        #expect(reader.delta(path: url.path) == 15)

        // The rest of that line arrives, and only now does it count — once.
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((String(line(input: 100, output: 100).dropFirst(40)) + "\n").utf8))
        try handle.close()

        #expect(reader.delta(path: url.path) == 200)
    }

    @Test("starts over when the file shrinks")
    func handlesTruncation() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        try (line(input: 10, output: 5) + "\n" + line(input: 10, output: 5) + "\n")
            .write(to: url, atomically: true, encoding: .utf8)

        var reader = TranscriptReader()
        #expect(reader.delta(path: url.path) == 30)

        // A shorter file at the same path is a different transcript. Reading on from the old
        // offset would land mid-line and count nothing ever again.
        try (line(input: 7, output: 0) + "\n").write(to: url, atomically: true, encoding: .utf8)
        #expect(reader.delta(path: url.path) == 7)
    }

    @Test("says zero about a file that is not there")
    func missingFile() {
        var reader = TranscriptReader()
        #expect(reader.delta(path: "/nonexistent/transcript.jsonl") == 0)
        #expect(reader.delta(path: "") == 0)
    }
}
