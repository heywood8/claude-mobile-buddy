import Foundation

/// Plaintext, carries no secrets: an opaque host id and a fresh salt.
struct Hello: Codable, Equatable {
    var t: String = "hello"
    var v: Int = 1
    let host: String
    let hs: String
}

/// The phone answers only if it knows the host and no other host is active.
struct Challenge: Codable, Equatable {
    var t: String = "challenge"
    var v: Int = 1
    let ps: String
}

/// First encrypted frame from the bridge. Opening it is the phone's proof that the bridge
/// holds the key; there is no separate challenge-response step.
struct Auth: Codable, Equatable {
    var t: String = "auth"
    let name: String
    /// Unix seconds and the host's UTC offset in seconds, so the phone can render the
    /// host's timestamps in the host's own clock.
    let time: [Int]
}

/// First encrypted frame from the phone, and the mirror of the same proof.
struct Ready: Codable, Equatable {
    var t: String = "ready"
    let device: String
    var proto: Int = 1
}

/// Everything a pairing QR code carries.
///
/// The bridge generates all of it and renders it; the phone only reads. A short typed code
/// was rejected during design: six digits is twenty bits, and a sniffed session would let
/// anyone brute-force the key offline in minutes.
struct PairingCode: Equatable {
    /// 32 lowercase hex characters. Opaque on purpose — it travels in the plaintext `hello`,
    /// so it must not leak a machine name.
    let hostID: String
    /// 32 bytes.
    let key: Data
    let hostName: String

    static let scheme = "cmb://pair"

    var url: String {
        var name = URLComponents()
        name.queryItems = [URLQueryItem(name: "n", value: hostName)]
        let encodedName = name.percentEncodedQuery ?? "n="
        return "\(Self.scheme)?h=\(hostID)&k=\(Base64URL.encode(key))&\(encodedName)"
    }

    static func generate(hostName: String) -> PairingCode {
        PairingCode(
            hostID: randomHex(16),
            key: randomBytes(32),
            hostName: hostName)
    }

    static func parse(_ text: String) -> PairingCode? {
        guard text.hasPrefix(scheme),
              let components = URLComponents(string: text),
              let items = components.queryItems else { return nil }
        let values = Dictionary(items.compactMap { item in
            item.value.map { (item.name, $0) }
        }, uniquingKeysWith: { first, _ in first })

        guard let hostID = values["h"],
              hostID.count == 32,
              hostID.allSatisfy({ $0.isHexDigit && !$0.isUppercase }),
              let keyText = values["k"],
              let key = Base64URL.decode(keyText),
              key.count == 32 else { return nil }
        return PairingCode(hostID: hostID, key: key, hostName: values["n"] ?? "")
    }

    private static func randomBytes(_ count: Int) -> Data {
        Data((0..<count).map { _ in UInt8.random(in: 0...255) })
    }

    private static func randomHex(_ byteCount: Int) -> String {
        randomBytes(byteCount).map { String(format: "%02x", $0) }.joined()
    }
}

enum Base64URL {
    static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ text: String) -> Data? {
        var padded = text
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while padded.count % 4 != 0 { padded.append("=") }
        return Data(base64Encoded: padded)
    }
}
