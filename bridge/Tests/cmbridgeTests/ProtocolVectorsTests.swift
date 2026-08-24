import Testing
import Foundation
import CryptoKit
@testable import cmbridge

/// Runs the bridge against `docs/protocol/fixtures/vectors.json` — the same file the Android
/// app's unit tests read. Neither implementation produced it; see the fixtures README.
@Suite("Protocol vectors")
struct ProtocolVectorsTests {
    // Walk up from this source file rather than bundling resources, so the two test suites
    // read the one file in the repository instead of private copies.
    static let vectors: Vectors = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        url.appendPathComponent("docs/protocol/fixtures/vectors.json")
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(Vectors.self, from: data) else {
            fatalError("cannot read vectors at \(url.path)")
        }
        return decoded
    }()

    // MARK: - Key derivation

    @Test("derives the key chain from the pre-shared key")
    func derivesTheKeyChain() throws {
        let keys = Self.vectors.keys
        let derived = SessionKeys.derive(
            psk: try hex(keys.pskHex),
            hostSalt: try hex(keys.hostSaltHex),
            phoneSalt: try hex(keys.phoneSaltHex))

        #expect(bytes(derived.session).hexString == keys.sessionHex)
        #expect(bytes(derived.hostToPhone).hexString == keys.hostToPhoneHex)
        #expect(bytes(derived.phoneToHost).hexString == keys.phoneToHostHex)
    }

    // MARK: - Frames

    @Test("seals every frame to the expected ciphertext")
    func sealsEachFrame() throws {
        for frame in Self.vectors.frames {
            let sealed = try Aead.seal(
                Data(frame.plaintext.utf8),
                counter: frame.counter,
                key: try key(for: frame.direction))
            #expect(
                sealed.base64EncodedString() == frame.ciphertextBase64,
                "direction \(frame.direction) counter \(frame.counter)")
        }
    }

    @Test("opens every frame back to its plaintext")
    func opensEachFrame() throws {
        for frame in Self.vectors.frames {
            let blob = try #require(Data(base64Encoded: frame.ciphertextBase64))
            let opened = try Aead.open(
                blob,
                counter: frame.counter,
                key: try key(for: frame.direction))
            #expect(String(decoding: opened, as: UTF8.self) == frame.plaintext)
        }
    }

    @Test("refuses a tampered frame")
    func refusesTamperedFrame() throws {
        let frame = Self.vectors.frames[0]
        var blob = try #require(Data(base64Encoded: frame.ciphertextBase64))
        blob[blob.startIndex] ^= 0x01
        let key = try key(for: frame.direction)

        #expect(throws: CryptoError.decryptFailed) {
            _ = try Aead.open(blob, counter: frame.counter, key: key)
        }
    }

    @Test("refuses a frame opened under the wrong counter")
    func refusesRenumberedFrame() throws {
        let frame = Self.vectors.frames[0]
        let blob = try #require(Data(base64Encoded: frame.ciphertextBase64))
        let key = try key(for: frame.direction)

        // The counter is authenticated, so renumbering a frame in flight cannot shift the
        // stream — it simply fails to open.
        #expect(throws: CryptoError.decryptFailed) {
            _ = try Aead.open(blob, counter: frame.counter + 1, key: key)
        }
    }

    // MARK: - Counter discipline

    @Test("round-trips a channel in order")
    func channelRoundTrip() throws {
        let (sending, receiving) = try channelPair()
        for text in ["first", "second", "third"] {
            let line = try sending.seal(Data(text.utf8))
            let opened = try receiving.open(stripNewline(line))
            #expect(String(decoding: opened, as: UTF8.self) == text)
        }
    }

    @Test("refuses a gap in the counter")
    func channelRefusesGap() throws {
        let (sending, receiving) = try channelPair()
        _ = try sending.seal(Data("dropped".utf8))
        let second = try sending.seal(Data("arrives".utf8))

        // A notification that never made it must surface as a torn session rather than
        // silently desynchronising the stream.
        #expect(throws: CryptoError.badCounter(expected: 0, got: 1)) {
            _ = try receiving.open(stripNewline(second))
        }
    }

    @Test("refuses a replayed frame")
    func channelRefusesReplay() throws {
        let (sending, receiving) = try channelPair()
        let line = try sending.seal(Data("once".utf8))
        _ = try receiving.open(stripNewline(line))

        #expect(throws: CryptoError.badCounter(expected: 1, got: 0)) {
            _ = try receiving.open(stripNewline(line))
        }
    }

    // MARK: - Pairing

    @Test("parses valid pairing codes")
    func parsesValidPairingCodes() throws {
        for expected in Self.vectors.pairing.valid {
            let parsed = try #require(PairingCode.parse(expected.url), "\(expected.url)")
            #expect(parsed.hostID == expected.hostId)
            #expect(parsed.key.hexString == expected.keyHex)
            #expect(parsed.hostName == expected.name)
        }
    }

    @Test("rejects malformed pairing codes")
    func rejectsMalformedPairingCodes() {
        for url in Self.vectors.pairing.invalid {
            #expect(PairingCode.parse(url) == nil, "\(url)")
        }
    }

    @Test("round-trips a generated pairing code")
    func generatedPairingCodeRoundTrips() throws {
        let generated = PairingCode.generate(hostName: "Someone's Mac & co")
        let parsed = try #require(PairingCode.parse(generated.url))
        #expect(parsed == generated)
    }

    // MARK: - Helpers

    private func key(for direction: String) throws -> SymmetricKey {
        let keys = Self.vectors.keys
        return SymmetricKey(
            data: try hex(direction == "h2p" ? keys.hostToPhoneHex : keys.phoneToHostHex))
    }

    private func channelPair() throws -> (SessionChannel, SessionChannel) {
        let keys = Self.vectors.keys
        let send = SymmetricKey(data: try hex(keys.hostToPhoneHex))
        let receive = SymmetricKey(data: try hex(keys.phoneToHostHex))
        return (
            SessionChannel(sendKey: send, receiveKey: receive),
            SessionChannel(sendKey: receive, receiveKey: send)
        )
    }

    private func stripNewline(_ line: Data) -> Data {
        line.last == 0x0A ? line.dropLast() : line
    }

    private func hex(_ text: String) throws -> Data {
        var data = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            guard let byte = UInt8(text[index..<next], radix: 16) else {
                throw HexError.malformed
            }
            data.append(byte)
            index = next
        }
        return data
    }

    private func bytes(_ key: SymmetricKey) -> Data {
        key.withUnsafeBytes { Data($0) }
    }
}

private enum HexError: Error { case malformed }

private extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

// MARK: - Fixture shapes

struct Vectors: Decodable {
    struct Keys: Decodable {
        let pskHex: String
        let hostSaltHex: String
        let phoneSaltHex: String
        let sessionHex: String
        let hostToPhoneHex: String
        let phoneToHostHex: String
    }

    struct Frame: Decodable {
        let direction: String
        let counter: UInt32
        let plaintext: String
        let ciphertextBase64: String
    }

    struct Pairing: Decodable {
        struct Valid: Decodable {
            let url: String
            let hostId: String
            let keyHex: String
            let name: String
        }

        let valid: [Valid]
        let invalid: [String]
    }

    let keys: Keys
    let frames: [Frame]
    let pairing: Pairing
}
