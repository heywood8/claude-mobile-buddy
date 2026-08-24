import Foundation

/// What a session wants the transport to do next.
enum SessionOutput: Equatable {
    /// Write this line to the peer.
    case send(Data)
    /// The handshake finished; application messages may flow.
    case ready
    /// A decrypted application line from the peer.
    case message(Data)
    /// Give up, for this reason.
    case close(String)
}

/// The bridge's half of the handshake and the encrypted stream, with the radio taken out.
///
/// Lines in, lines out, no CoreBluetooth anywhere — which is what lets the whole protocol be
/// exercised in tests. `BLELink` is left as a transport that knows nothing about the protocol.
final class HostSession {
    enum State: Equatable {
        case idle
        case awaitingChallenge
        case awaitingReady
        case ready
        case closed
    }

    private(set) var state: State = .idle

    private let pairing: PairingCode
    private let hostSalt: Data
    private let clock: () -> Date
    private let utcOffset: () -> Int
    private var channel: SessionChannel?

    init(
        pairing: PairingCode,
        hostSalt: Data = SessionSalt.random(),
        clock: @escaping () -> Date = Date.init,
        utcOffset: @escaping () -> Int = { TimeZone.current.secondsFromGMT() }
    ) {
        self.pairing = pairing
        self.hostSalt = hostSalt
        self.clock = clock
        self.utcOffset = utcOffset
    }

    /// Opens with the only line that travels in the clear: an opaque host id and a fresh salt.
    func start() -> [SessionOutput] {
        guard state == .idle else { return [] }
        let hello = Hello(host: pairing.hostID, hs: hostSalt.base64EncodedString())
        guard let line = try? LineCodec.encode(hello) else {
            return close("bad_frame")
        }
        state = .awaitingChallenge
        return [.send(line)]
    }

    func receive(_ line: Data) -> [SessionOutput] {
        switch state {
        case .idle, .closed:
            return []

        case .awaitingChallenge:
            // Before the channel exists the only things the peer can legitimately say are
            // "here is my salt" and "go away".
            if let bye = try? JSONDecoder().decode(Bye.self, from: line), bye.t == "bye" {
                return close(bye.reason)
            }
            guard let challenge = try? JSONDecoder().decode(Challenge.self, from: line),
                  challenge.t == "challenge" else {
                return close("bad_frame")
            }
            guard challenge.v == Self.version else { return close("version") }
            guard let phoneSalt = Data(base64Encoded: challenge.ps),
                  phoneSalt.count == SessionSalt.byteCount else {
                return close("bad_frame")
            }

            let keys = SessionKeys.derive(
                psk: pairing.key, hostSalt: hostSalt, phoneSalt: phoneSalt)
            let channel = SessionChannel(
                sendKey: keys.hostToPhone, receiveKey: keys.phoneToHost)
            self.channel = channel

            let auth = Auth(
                name: pairing.hostName,
                time: [Int(clock().timeIntervalSince1970), utcOffset()])
            guard let plaintext = try? LineCodec.payload(auth),
                  let sealed = try? channel.seal(plaintext) else {
                return close("bad_frame")
            }
            state = .awaitingReady
            return [.send(sealed)]

        case .awaitingReady:
            guard let plaintext = open(line) else { return close("bad_frame") }
            guard let ready = try? JSONDecoder().decode(Ready.self, from: plaintext),
                  ready.t == "ready" else {
                return close("bad_frame")
            }
            guard ready.proto == Self.version else { return close("version") }
            state = .ready
            return [.ready]

        case .ready:
            guard let plaintext = open(line) else { return close("bad_frame") }
            return [.message(plaintext)]
        }
    }

    /// Wraps an application line for the wire. Only valid once ready.
    func seal(_ plaintext: Data) -> Data? {
        guard state == .ready, let channel else { return nil }
        return try? channel.seal(plaintext)
    }

    // MARK: - Internals

    static let version = 1

    private func open(_ line: Data) -> Data? {
        // A frame that will not open is not a hiccup to skip past: either the key is wrong or
        // someone is editing the stream. Either way the session is over.
        guard let channel, let trimmed = Self.trimNewline(line) else { return nil }
        return try? channel.open(trimmed)
    }

    private func close(_ reason: String) -> [SessionOutput] {
        state = .closed
        channel = nil
        var outputs: [SessionOutput] = []
        if let line = try? LineCodec.encode(Bye(reason: reason)) {
            outputs.append(.send(line))
        }
        outputs.append(.close(reason))
        return outputs
    }

    private static func trimNewline(_ line: Data) -> Data? {
        line.last == 0x0A ? line.dropLast() : line
    }
}

enum SessionSalt {
    static let byteCount = 32

    static func random() -> Data {
        Data((0..<byteCount).map { _ in UInt8.random(in: 0...255) })
    }
}
