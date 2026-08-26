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

    @Test("counts new tokens and ignores the cache re-read")
    func sumsUsage() {
        // The thousand cache reads are the same context arriving again. Counting them would
        // put a number on the screen that grows with the square of the conversation.
        let data = Data(line(input: 12, output: 34, cacheRead: 1000).utf8)
        #expect(TranscriptReader.tokens(inLine: data) == 46)
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
        #expect(reader.total(path: url.path) == 15)
        // Same file, same answer. The total is what the caller stores, so reading it again —
        // which happens after every tool call — must not move it.
        #expect(reader.total(path: url.path) == 15)

        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((line(input: 1, output: 2) + "\n").utf8))
        try handle.close()

        #expect(reader.total(path: url.path) == 18)
    }

    @Test("leaves a half-written line for the next pass")
    func waitsForTheNewline() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        let complete = line(input: 10, output: 5) + "\n"
        let partial = String(line(input: 100, output: 100).prefix(40))
        try (complete + partial).write(to: url, atomically: true, encoding: .utf8)

        var reader = TranscriptReader()
        #expect(reader.total(path: url.path) == 15)

        // The rest of that line arrives, and only now does it count — once.
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data((String(line(input: 100, output: 100).dropFirst(40)) + "\n").utf8))
        try handle.close()

        #expect(reader.total(path: url.path) == 215)
    }

    @Test("starts over when the file shrinks")
    func handlesTruncation() throws {
        let url = temporaryFile()
        defer { try? FileManager.default.removeItem(at: url) }
        try (line(input: 10, output: 5) + "\n" + line(input: 10, output: 5) + "\n")
            .write(to: url, atomically: true, encoding: .utf8)

        var reader = TranscriptReader()
        #expect(reader.total(path: url.path) == 30)

        // A shorter file at the same path is a different transcript. Reading on from the old
        // offset would land mid-line and count nothing ever again.
        try (line(input: 7, output: 0) + "\n").write(to: url, atomically: true, encoding: .utf8)
        #expect(reader.total(path: url.path) == 7)
    }

    @Test("says zero about a file that is not there")
    func missingFile() {
        var reader = TranscriptReader()
        #expect(reader.total(path: "/nonexistent/transcript.jsonl") == 0)
        #expect(reader.total(path: "") == 0)
    }
}
