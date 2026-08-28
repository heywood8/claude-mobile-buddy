import Testing
import Foundation
import AppKit
import CryptoKit
@testable import cmbridge

/// The wire shape pinned here is pinned identically in the Android suite
/// (`ClipboardTest.kt`). It is not in `vectors.json`: that file pins ciphertexts for fixed
/// plaintexts, and a new message type does not change any sealed bytes.
@Suite("Clipboard")
struct ClipboardTests {
    static let sample = "hello from the Mac"
    static let sampleLine = #"{"at":1775731300,"b":"aGVsbG8gZnJvbSB0aGUgTWFj","t":"clip"}"#

    // MARK: - Codec

    @Test("encodes to the shape both implementations agree on")
    func encodesToTheAgreedShape() throws {
        let clip = Clip.of(Self.sample, at: 1_775_731_300)
        let encoded = String(decoding: try LineCodec.payload(clip), as: UTF8.self)
        #expect(encoded == Self.sampleLine)
    }

    @Test("round-trips text through base64")
    func roundTripsText() {
        #expect(Clip.of(Self.sample, at: 1).text == Self.sample)
        #expect(Clip.of("", at: 1).text == "")
        #expect(Clip.of("ключ · 鍵 · 🦀", at: 1).text == "ключ · 鍵 · 🦀")
    }

    @Test("reads as a clip off the wire, alongside the messages that were there before")
    func decodesAsAClip() throws {
        guard case .clip(let clip) = try LineCodec.decode(Data(Self.sampleLine.utf8)) else {
            Issue.record("not decoded as a clip")
            return
        }
        #expect(clip.text == Self.sample)
        #expect(clip.at == 1_775_731_300)

        // The other two still decode as themselves: Clip is tried between them.
        let decision = #"{"cmd":"permission","decision":"once","id":"req_abc123"}"#
        guard case .decision = try LineCodec.decode(Data(decision.utf8)) else {
            Issue.record("a decision no longer decodes as one")
            return
        }
        guard case .bye = try LineCodec.decode(Data(#"{"reason":"shutdown","t":"bye"}"#.utf8)) else {
            Issue.record("a bye no longer decodes as one")
            return
        }
    }

    @Test("a clip whose payload is not base64, or not UTF-8 inside it, decodes to nothing")
    func rejectsRubbish() {
        #expect(Clip(b: "not base64!!", at: 0).text == nil)
        // 0xFF is not a legal UTF-8 byte anywhere.
        #expect(Clip(b: Data([0xFF, 0xFE]).base64EncodedString(), at: 0).text == nil)
    }

    // MARK: - The size cap

    @Test("clamps to the byte limit")
    func clampsToTheLimit() throws {
        let text = String(repeating: "a", count: Clip.textLimit + 500)
        let clipped = try #require(Clip.of(text, at: 0).text)
        #expect(clipped.utf8.count == Clip.textLimit)
        // No ellipsis: a clipboard is pasted, and a marker on the end would be pasted too.
        #expect(!clipped.hasSuffix("…"))
    }

    @Test("clamps on a character boundary rather than through one")
    func clampsOnACharacterBoundary() throws {
        // Four bytes each, so the limit falls inside a character rather than between two.
        let text = String(repeating: "🦀", count: Clip.textLimit)
        let clipped = try #require(Clip.of(text, at: 0).text, "cut mid-character, so it did not decode")
        #expect(clipped.utf8.count <= Clip.textLimit)
        #expect(clipped.utf8.count > Clip.textLimit - 4)
        #expect(clipped.allSatisfy { $0 == "🦀" })
    }

    /// The reason the text travels base64 at all.
    ///
    /// As a JSON string, a control character costs six bytes rather than one, so a clip of the
    /// wrong shape would seal to a line over `LineCodec.maxLine` — and an oversized line is not
    /// dropped politely. The assembler throws it away, whatever follows the newline decrypts as
    /// garbage, and the session ends. This is the test that says it cannot happen.
    @Test("a worst-case clip still seals under the line cap")
    func staysUnderTheLineCap() throws {
        let key = SymmetricKeyForTests.zeroes
        for text in [
            String(repeating: "\u{01}", count: Clip.textLimit * 2),
            String(repeating: "\"", count: Clip.textLimit * 2),
            String(repeating: "🦀", count: Clip.textLimit),
            String(repeating: "a", count: Clip.textLimit * 2),
        ] {
            let clip = Clip.of(text, at: 9_999_999_999)
            let payload = try LineCodec.payload(clip)
            let channel = SessionChannel(sendKey: key, receiveKey: key)
            let line = try channel.seal(payload)
            #expect(line.count <= LineCodec.maxLine, "sealed to \(line.count) bytes")
        }
    }

    // MARK: - The mirror

    @Test("sends what was copied, once")
    func sendsWhatWasCopied() {
        let (link, mirror, board) = harness()

        board.clearContents()
        board.setString("first", forType: .string)
        mirror.poll()
        #expect(clip(from: link)?.text == "first")

        // Nothing new on the pasteboard is nothing to say, keepalive or no keepalive.
        let before = link.snapshotCount
        mirror.poll()
        #expect(link.snapshotCount == before)
    }

    /// The loop this whole design exists to prevent: a clip that arrives, is applied, and is
    /// read straight back as though it were something you had just copied yourself.
    @Test("does not send back what the phone just sent")
    func doesNotEchoThePhone() {
        let (link, mirror, board) = harness()

        mirror.receive(Clip.of("from the phone", at: 0))
        mirror.settle()
        #expect(board.string(forType: .string) == "from the phone")

        mirror.poll()
        #expect(link.snapshotCount == 0, "applied a clip and sent it straight back")
    }

    @Test("ignores a clip it already has, whichever way it arrives")
    func ignoresWhatItAlreadyHas() {
        let (link, mirror, board) = harness()

        board.clearContents()
        board.setString("same", forType: .string)
        mirror.poll()
        #expect(link.snapshotCount == 1)

        // The phone answering with the text we just sent it changes nothing here.
        let count = board.changeCount
        mirror.receive(Clip.of("same", at: 0))
        mirror.settle()
        #expect(board.changeCount == count, "rewrote the pasteboard with what was on it")
    }

    @Test("never sends a clip a password manager marked concealed")
    func skipsConcealedClips() {
        for marker in PasteboardMirror.concealed {
            let (link, mirror, board) = harness()
            board.clearContents()
            board.setString("hunter2", forType: .string)
            board.setString("", forType: .init(marker))
            mirror.poll()
            #expect(link.snapshotCount == 0, "sent a clip marked \(marker)")
        }
    }

    /// A concealed clip must not advance the mirror either, or the ordinary copy of the same
    /// text afterwards would look like something already agreed on and never be sent.
    @Test("a concealed clip does not swallow the next ordinary one")
    func concealedDoesNotPoisonTheMirror() {
        let (link, mirror, board) = harness()

        board.clearContents()
        board.setString("shared", forType: .string)
        board.setString("", forType: .init("org.nspasteboard.ConcealedType"))
        mirror.poll()
        #expect(link.snapshotCount == 0)

        board.clearContents()
        board.setString("shared", forType: .string)
        mirror.poll()
        #expect(clip(from: link)?.text == "shared")
    }

    @Test("what is already on the pasteboard at startup is not news")
    func doesNotPushWhatWasAlreadyThere() {
        let board = privateBoard()
        board.clearContents()
        board.setString("copied before the bridge started", forType: .string)

        let link = FakeLink()
        let mirror = PasteboardMirror(link: link, log: Logger(), board: board)
        mirror.poll()
        #expect(link.snapshotCount == 0, "pushed the clipboard at the first phone to connect")
    }

    // MARK: - Helpers

    /// A pasteboard of its own per test, so running the suite does not walk over whatever the
    /// person running it had copied.
    private func privateBoard() -> NSPasteboard {
        NSPasteboard(name: .init("cmbridge.tests.\(UUID().uuidString)"))
    }

    private func harness() -> (FakeLink, PasteboardMirror, NSPasteboard) {
        let board = privateBoard()
        board.clearContents()
        let link = FakeLink()
        return (link, PasteboardMirror(link: link, log: Logger(), board: board), board)
    }

    private func clip(from link: FakeLink) -> Clip? {
        guard let line = link.lastLine else { return nil }
        return try? JSONDecoder().decode(Clip.self, from: line)
    }
}

enum SymmetricKeyForTests {
    static let zeroes = SymmetricKey(data: Data(repeating: 0, count: 32))
}
