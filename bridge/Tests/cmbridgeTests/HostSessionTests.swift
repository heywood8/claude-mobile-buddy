import Testing
import Foundation
@testable import cmbridge

/// Drives `HostSession` against the recorded handshake in the shared vectors, with the salts
/// and clock pinned so the frames it produces are the ones the Android side replays.
@Suite("Host session")
struct HostSessionTests {
    private var handshake: Handshake { Handshake.load() }

    private func makeSession() -> HostSession {
        HostSession(
            pairing: PairingCode(
                hostID: handshake.hostId,
                key: handshake.psk,
                hostName: handshake.hostName),
            hostSalt: handshake.hostSalt,
            clock: { Date(timeIntervalSince1970: TimeInterval(handshake.authTime[0])) },
            utcOffset: { handshake.authTime[1] })
    }

    @Test("opens with the recorded hello")
    func opensWithHello() throws {
        let session = makeSession()
        let outputs = session.start()

        let line = try #require(outputs.first?.sentLine)
        let sent = try JSONDecoder().decode(Hello.self, from: line)
        let expected = try JSONDecoder().decode(Hello.self, from: Data(handshake.hello.utf8))

        // Compared as parsed objects: key order is not part of the protocol.
        #expect(sent == expected)
        #expect(session.state == .awaitingChallenge)
    }

    @Test("answers the challenge with an auth frame the phone can open")
    func answersChallenge() throws {
        let session = makeSession()
        _ = session.start()

        let outputs = session.receive(Data(handshake.challenge.utf8))
        let line = try #require(outputs.first?.sentLine)
        let frame = try JSONDecoder().decode(Frame.self, from: line)
        #expect(frame.n == 0)

        // Opened with the derived key rather than compared byte for byte: JSON key order is
        // not part of the protocol, and the two serialisers do not agree on it.
        let blob = try #require(Data(base64Encoded: frame.c))
        let key = SessionKeys.derive(
            psk: handshake.psk,
            hostSalt: handshake.hostSalt,
            phoneSalt: handshake.phoneSalt).hostToPhone
        let plaintext = try Aead.open(blob, counter: 0, key: key)
        let auth = try JSONDecoder().decode(Auth.self, from: plaintext)

        #expect(auth.t == "auth")
        #expect(auth.name == handshake.hostName)
        #expect(auth.time == handshake.authTime)
        #expect(session.state == .awaitingReady)
    }

    @Test("becomes ready on the recorded ready frame")
    func becomesReady() throws {
        let session = try readySession()
        #expect(session.state == .ready)
    }

    @Test("carries application messages once ready")
    func carriesMessages() throws {
        let session = try readySession()

        // Counter 1 in the phone-to-host direction is the decision frame in the vectors.
        let decisionFrame = Self.vectorFrame(direction: "p2h", counter: 1)
        let outputs = session.receive(try line(counter: 1, ciphertext: decisionFrame))

        let plaintext = try #require(outputs.first?.messagePayload)
        let decoded = try JSONDecoder().decode(Decision.self, from: plaintext)
        #expect(decoded.id == "req_abc123")
        #expect(decoded.decision == .once)
    }

    @Test("refuses to seal before the handshake finishes")
    func refusesEarlySeal() {
        let session = makeSession()
        _ = session.start()
        #expect(session.seal(Data("{}".utf8)) == nil)
    }

    @Test("closes on a challenge from the wrong protocol version")
    func closesOnVersionMismatch() throws {
        let session = makeSession()
        _ = session.start()

        let challenge = #"{"ps":"\#(handshake.phoneSalt.base64EncodedString())","t":"challenge","v":99}"#
        let outputs = session.receive(Data(challenge.utf8))

        #expect(outputs.contains(.close("version")))
        #expect(session.state == .closed)
        // A reason goes out before the door shuts, so the other side is not left guessing.
        let bye = try #require(outputs.first?.sentLine)
        #expect(try JSONDecoder().decode(Bye.self, from: bye).reason == "version")
    }

    @Test("closes on a frame it cannot open")
    func closesOnUnopenableFrame() throws {
        let session = makeSession()
        _ = session.start()
        _ = session.receive(Data(handshake.challenge.utf8))

        let garbage = try line(counter: 0, ciphertext: Data(repeating: 0x7A, count: 40).base64EncodedString())
        let outputs = session.receive(garbage)

        #expect(outputs.contains(.close("bad_frame")))
        #expect(session.state == .closed)
    }

    @Test("stops talking once closed")
    func stopsAfterClose() throws {
        let session = makeSession()
        _ = session.start()
        _ = session.receive(Data(#"{"t":"bye","reason":"unknown_host"}"#.utf8))

        #expect(session.state == .closed)
        #expect(session.receive(Data(handshake.challenge.utf8)).isEmpty)
        #expect(session.seal(Data("{}".utf8)) == nil)
    }

    // MARK: - Helpers

    private func readySession() throws -> HostSession {
        let session = makeSession()
        _ = session.start()
        _ = session.receive(Data(handshake.challenge.utf8))
        _ = session.receive(try line(counter: 0, ciphertext: handshake.readyCiphertextBase64))
        return session
    }

    private func line(counter: UInt32, ciphertext: String) throws -> Data {
        try LineCodec.encode(Frame(n: counter, c: ciphertext))
    }

    private static func vectorFrame(direction: String, counter: UInt32) -> String {
        Fixtures.all.frames
            .first { $0.direction == direction && $0.counter == counter }!
            .ciphertextBase64
    }
}

private extension SessionOutput {
    var sentLine: Data? { if case .send(let line) = self { return line } else { return nil } }
    var messagePayload: Data? {
        if case .message(let payload) = self { return payload } else { return nil }
    }
}

/// The handshake slice of the shared vectors.
struct Handshake: Decodable {
    let hostId: String
    let hostName: String
    let deviceName: String
    let hostSaltBase64: String
    let phoneSaltBase64: String
    let hello: String
    let challenge: String
    let authCiphertextBase64: String
    let readyCiphertextBase64: String
    let authTime: [Int]

    var hostSalt: Data { Data(base64Encoded: hostSaltBase64)! }
    var phoneSalt: Data { Data(base64Encoded: phoneSaltBase64)! }
    var psk: Data { Fixtures.hexToData(Fixtures.all.keys.pskHex) }

    static func load() -> Handshake { Fixtures.all.handshake }
}

/// `Vectors` plus the handshake section — the whole file, decoded once.
struct VectorsWithHandshake: Decodable {
    let keys: Vectors.Keys
    let frames: [Vectors.Frame]
    let pairing: Vectors.Pairing
    let handshake: Handshake
}

enum Fixtures {
    static let all: VectorsWithHandshake = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        url.appendPathComponent("docs/protocol/fixtures/vectors.json")
        guard let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(VectorsWithHandshake.self, from: data) else {
            fatalError("cannot read vectors at \(url.path)")
        }
        return decoded
    }()

    static func hexToData(_ text: String) -> Data {
        var data = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            data.append(UInt8(text[index..<next], radix: 16)!)
            index = next
        }
        return data
    }
}
