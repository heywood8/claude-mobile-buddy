import Foundation

/// The bridge's own identity and pre-shared key, kept between runs.
///
/// One identity per install. `pair` re-renders the same code rather than minting a new one,
/// so showing the QR again on a second phone does not silently unpair the first.
struct Identity: Codable, Equatable {
    let hostID: String
    let keyBase64URL: String
    let hostName: String

    var pairingCode: PairingCode? {
        guard let key = Base64URL.decode(keyBase64URL) else { return nil }
        return PairingCode(hostID: hostID, key: key, hostName: hostName)
    }

    static func from(_ code: PairingCode) -> Identity {
        Identity(
            hostID: code.hostID,
            keyBase64URL: Base64URL.encode(code.key),
            hostName: code.hostName)
    }
}

enum IdentityStore {
    static var directory: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".config/claude-mobile-buddy", isDirectory: true)
    }

    static var file: URL { directory.appendingPathComponent("identity.json") }

    static func load() -> Identity? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode(Identity.self, from: data)
    }

    /// Returns the stored identity, creating one on first use.
    static func loadOrCreate(hostName: String, rotate: Bool = false) throws -> Identity {
        if !rotate, let existing = load() { return existing }
        let identity = Identity.from(PairingCode.generate(hostName: hostName))
        try save(identity)
        return identity
    }

    static func save(_ identity: Identity) throws {
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(identity)

        // The file holds the key that authorises approving shell commands on this machine.
        // Create it unreadable to anyone else before the bytes land in it.
        let path = file.path
        if !FileManager.default.createFile(
            atPath: path,
            contents: data,
            attributes: [.posixPermissions: 0o600]
        ) {
            try data.write(to: file, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
        }
    }

    static func defaultHostName() -> String {
        let name = Host.current().localizedName ?? ProcessInfo.processInfo.hostName
        return name.isEmpty ? "Mac" : name
    }
}
