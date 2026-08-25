import Foundation

/// Puts `HostSession` between the coordinator and the radio.
///
/// The coordinator still talks to a `LinkSink` and knows nothing about keys; `BLELink` still
/// moves bytes and knows nothing about the protocol. This is the only place that holds both.
///
/// `isLinked` reports false until the handshake has finished, so a connected-but-unauthenticated
/// peer cannot make the coordinator hold a terminal open waiting for an answer that can never
/// arrive.
final class SecureLink: LinkSink, @unchecked Sendable {
    private let transport: BLELink
    private let identity: PairingCode
    private let log: Logger

    /// All session state is touched only here. `HostSession` is not thread-safe, and the
    /// transport calls back on a queue of its own.
    private let queue = DispatchQueue(label: "cmbridge.session")
    private var session: HostSession?

    private let lock = NSLock()
    private var _isLinked = false

    /// Called with each decrypted application line.
    var onLine: ((Data) -> Void)?
    /// Called when the session becomes usable, and when it stops being usable.
    var onLinkChange: ((Bool) -> Void)?

    init(transport: BLELink, identity: PairingCode, log: Logger) {
        self.transport = transport
        self.identity = identity
        self.log = log

        transport.onLinkChange = { [weak self] up in
            self?.queue.async { up ? self?.begin() : self?.end("transport down") }
        }
        transport.onLine = { [weak self] line in
            self?.queue.async { self?.handle(line) }
        }
    }

    var isLinked: Bool {
        lock.lock(); defer { lock.unlock() }
        return _isLinked
    }

    func send(_ line: Data) {
        queue.async { [weak self] in
            guard let self, let session = self.session else { return }
            // The framing newline belongs to the outer stream. Inside a frame it would be an
            // encrypted byte that means nothing to the reader — and would put us one byte
            // away from the Android side. See docs/PROTOCOL.md.
            let payload = line.last == 0x0A ? line.dropLast() : line[...]
            guard let frame = session.seal(Data(payload)) else { return }
            self.transport.send(frame)
        }
    }

    // MARK: - Session lifecycle

    private func begin() {
        let session = HostSession(pairing: identity)
        self.session = session
        log.info("handshaking as \(identity.hostID.prefix(8))…")
        apply(session.start())
    }

    private func handle(_ line: Data) {
        guard let session else { return }
        apply(session.receive(line))
    }

    private func apply(_ outputs: [SessionOutput]) {
        for output in outputs {
            switch output {
            case .send(let line):
                transport.send(line)
            case .ready:
                log.info("session ready, channel encrypted")
                setLinked(true)
            case .message(let payload):
                onLine?(payload)
            case .close(let reason):
                end(reason)
            }
        }
    }

    private func end(_ reason: String) {
        let wasLinked = isLinked
        session = nil
        setLinked(false)
        if wasLinked || reason != "transport down" {
            log.info("session over: \(reason)")
        }
        // A session that ended for a protocol reason will not recover by staying connected;
        // dropping the link restarts the handshake from scratch.
        if reason != "transport down" {
            transport.disconnect()
        }
    }

    private func setLinked(_ value: Bool) {
        lock.lock()
        let changed = _isLinked != value
        _isLinked = value
        lock.unlock()
        if changed { onLinkChange?(value) }
    }
}
