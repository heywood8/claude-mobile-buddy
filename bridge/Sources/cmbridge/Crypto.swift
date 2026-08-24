import Foundation
import CryptoKit

enum CryptoError: Error, Equatable {
    case shortFrame
    case badCounter(expected: UInt32, got: UInt32)
    case decryptFailed
    case counterExhausted
}

/// Keys for one connection.
///
/// Derived fresh per session so the AES-GCM counter can start at zero every time without ever
/// reusing a nonce under the long-lived pre-shared key. Separate keys per direction mean a
/// frame cannot be reflected back at its sender.
struct SessionKeys {
    let session: SymmetricKey
    let hostToPhone: SymmetricKey
    let phoneToHost: SymmetricKey

    /// Non-empty salts throughout: RFC 5869 lets an absent salt mean a string of zeros, and
    /// two implementations can disagree about whether "empty" means absent.
    static let domain = Data("cmb/v1".utf8)

    static func derive(psk: Data, hostSalt: Data, phoneSalt: Data) -> SessionKeys {
        let session = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: psk),
            salt: hostSalt + phoneSalt,
            info: Data("cmb/v1/session".utf8),
            outputByteCount: 32)
        return SessionKeys(
            session: session,
            hostToPhone: HKDF<SHA256>.deriveKey(
                inputKeyMaterial: session, salt: domain,
                info: Data("h2p".utf8), outputByteCount: 32),
            phoneToHost: HKDF<SHA256>.deriveKey(
                inputKeyMaterial: session, salt: domain,
                info: Data("p2h".utf8), outputByteCount: 32))
    }
}

enum Aead {
    static let tagBytes = 16

    /// Twelve bytes: eight zeros then the counter, big-endian.
    static func nonce(_ counter: UInt32) -> Data {
        var data = Data(repeating: 0, count: 8)
        data.append(contentsOf: [
            UInt8(truncatingIfNeeded: counter >> 24),
            UInt8(truncatingIfNeeded: counter >> 16),
            UInt8(truncatingIfNeeded: counter >> 8),
            UInt8(truncatingIfNeeded: counter),
        ])
        return data
    }

    /// The counter is authenticated as well as carried in the nonce, so a frame renumbered in
    /// flight fails to open rather than silently shifting the stream.
    static func aad(_ counter: UInt32) -> Data { Data(String(counter).utf8) }

    static func seal(_ plaintext: Data, counter: UInt32, key: SymmetricKey) throws -> Data {
        let box = try AES.GCM.seal(
            plaintext,
            using: key,
            nonce: AES.GCM.Nonce(data: nonce(counter)),
            authenticating: aad(counter))
        return box.ciphertext + box.tag
    }

    static func open(_ blob: Data, counter: UInt32, key: SymmetricKey) throws -> Data {
        guard blob.count > tagBytes else { throw CryptoError.shortFrame }
        let split = blob.index(blob.endIndex, offsetBy: -tagBytes)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: nonce(counter)),
                ciphertext: blob[blob.startIndex..<split],
                tag: blob[split...])
            return try AES.GCM.open(box, using: key, authenticating: aad(counter))
        } catch {
            throw CryptoError.decryptFailed
        }
    }
}

/// One line of the encrypted stream.
struct Frame: Codable, Equatable {
    let n: UInt32
    let c: String
}

/// Applies the counter rules on top of `Aead`.
///
/// Counters are explicit in the frame rather than implied by arrival order, so a dropped
/// notification is detected instead of silently desynchronising the stream.
final class SessionChannel {
    private let sendKey: SymmetricKey
    private let receiveKey: SymmetricKey
    private var nextSend: UInt32 = 0
    private var nextReceive: UInt32 = 0

    init(sendKey: SymmetricKey, receiveKey: SymmetricKey) {
        self.sendKey = sendKey
        self.receiveKey = receiveKey
    }

    func seal(_ plaintext: Data) throws -> Data {
        let counter = nextSend
        guard counter != UInt32.max else { throw CryptoError.counterExhausted }
        let blob = try Aead.seal(plaintext, counter: counter, key: sendKey)
        nextSend += 1
        let frame = Frame(n: counter, c: blob.base64EncodedString())
        return try LineCodec.encode(frame)
    }

    func open(_ line: Data) throws -> Data {
        let frame = try JSONDecoder().decode(Frame.self, from: line)
        guard frame.n == nextReceive else {
            throw CryptoError.badCounter(expected: nextReceive, got: frame.n)
        }
        guard let blob = Data(base64Encoded: frame.c) else { throw CryptoError.shortFrame }
        let plaintext = try Aead.open(blob, counter: frame.n, key: receiveKey)
        nextReceive += 1
        return plaintext
    }
}
