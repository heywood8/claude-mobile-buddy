import Foundation

let log = Logger()

var arguments = Array(CommandLine.arguments.dropFirst())

func takeFlag(_ name: String) -> Bool {
    guard let index = arguments.firstIndex(of: name) else { return false }
    arguments.remove(at: index)
    return true
}

func takeValue(_ name: String) -> String? {
    guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else {
        return nil
    }
    let value = arguments[index + 1]
    arguments.removeSubrange(index...(index + 1))
    return value
}

let rotate = takeFlag("--rotate")
let showURL = takeFlag("--url")
let port = takeValue("--port").flatMap(Int.init) ?? 8787
let window = takeValue("--window").flatMap(TimeInterval.init) ?? Coordinator.defaultWindow

func hookSnippet(port: Int, window: TimeInterval) -> String {
    let base = "http://127.0.0.1:\(port)"
    // Derived from the bridge's own window and deliberately a minute longer, so the bridge is
    // always the side that decides to give up. Set it lower than the window and the hook
    // abandons the request while your phone is still showing it.
    let permissionTimeout = Int(window) + 60
    return """
    {
      "hooks": {
        "PermissionRequest": [
          { "hooks": [ { "type": "http", "url": "\(base)/permission-request", "timeout": \(permissionTimeout) } ] }
        ],
        "SessionStart": [
          { "hooks": [ { "type": "http", "url": "\(base)/session-start", "timeout": 5 } ] }
        ],
        "SessionEnd": [
          { "hooks": [ { "type": "http", "url": "\(base)/session-end", "timeout": 5 } ] }
        ],
        "PostToolUse": [
          { "matcher": "Bash|Write|Edit|Task",
            "hooks": [ { "type": "http", "url": "\(base)/tool-use", "timeout": 5 } ] }
        ]
      }
    }
    """
}

switch arguments.first {
case "print-hook":
    print(hookSnippet(port: port, window: window))

case "pair":
    do {
        let identity = try IdentityStore.loadOrCreate(
            hostName: IdentityStore.defaultHostName(), rotate: rotate)
        guard let code = identity.pairingCode else {
            log.error("stored identity is unreadable; run `cmbridge pair --rotate`")
            exit(1)
        }
        guard let rendered = QRCode.render(code.url) else {
            log.error("could not render the QR code")
            exit(1)
        }
        if let needed = QRCode.columns(for: code.url),
           let available = terminalColumns(),
           available < needed {
            log.error("terminal is \(available) columns, the code needs \(needed)")
            log.error("widen the window and run this again — a wrapped code cannot be scanned")
            exit(1)
        }
        print()
        print(rendered)
        print()
        print("Scan this in Claude Buddy on your phone.")
        print("Host: \(code.hostName)  ·  id \(code.hostID.prefix(8))…")
        if rotate {
            print("A new key was generated. Phones paired with the old one no longer work.")
        }
        if showURL {
            // The payload is the key. Anything that can read your scrollback can pair.
            print()
            print("Payload (contains the key — do not paste this anywhere):")
            print(code.url)
        }
        print()
    } catch {
        log.error("could not write the identity: \(error)")
        exit(1)
    }

case "status":
    guard let identity = IdentityStore.load() else {
        print("Not paired yet. Run `cmbridge pair`.")
        exit(0)
    }
    // Never the key itself: this is meant to be safe to paste into a bug report.
    print("Host name : \(identity.hostName)")
    print("Host id   : \(identity.hostID)")
    print("Identity  : \(IdentityStore.file.path)")
    print("Listening : \(probe(port: port) ? "yes on \(port)" : "no")")

case "run", nil:
    // No pairing, no session, no plaintext fallback. Refusing to start beats starting and
    // silently never being able to talk to anything.
    guard let identity = IdentityStore.load()?.pairingCode else {
        log.error("not paired yet — run `cmbridge pair` and scan the code on your phone")
        exit(1)
    }
    let transport = BLELink(log: log)
    let link = SecureLink(transport: transport, identity: identity, log: log)
    log.info("approval window \(Int(window) / 60) min \(Int(window) % 60) s")
    let coordinator = Coordinator(link: link, log: log, window: window)

    link.onLine = { line in
        do {
            switch try LineCodec.decode(line) {
            case .decision(let decision):
                Task { await coordinator.resolve(decision) }
            case .bye(let reason):
                log.info("phone said bye: \(reason)")
            }
        } catch {
            log.error("bad line from phone: \(error)")
        }
    }
    link.onLinkChange = { up in
        Task { await coordinator.linkChanged(up) }
    }

    Task {
        while true {
            try? await Task.sleep(nanoseconds: UInt64(Snapshot.keepalive * 1_000_000_000))
            await coordinator.keepalive()
        }
    }

    do {
        try HookServer(coordinator: coordinator, log: log).run(port: port)
    } catch {
        log.error("cannot listen on \(port): \(error)")
        exit(1)
    }

default:
    print("""
    cmbridge — Claude Code approvals on your phone

      cmbridge run [--port N] [--window SECONDS]
                                       run the bridge (port 8787, window 30 min)
      cmbridge pair [--rotate] [--url] show the pairing QR code
      cmbridge status [--port N]       show the identity and whether the bridge is up
      cmbridge print-hook [--port N] [--window SECONDS]
                                       print the settings.json snippet to paste

    The bridge never edits ~/.claude/settings.json for you.
    """)
    exit(2)
}

/// How wide the terminal is, or nil when stdout is not one.
func terminalColumns() -> Int? {
    var size = winsize()
    guard ioctl(STDOUT_FILENO, UInt(TIOCGWINSZ), &size) == 0, size.ws_col > 0 else { return nil }
    return Int(size.ws_col)
}

/// Is something answering on the bridge's port right now?
func probe(port: Int) -> Bool {
    guard let url = URL(string: "http://127.0.0.1:\(port)/health") else { return false }
    var request = URLRequest(url: url)
    request.timeoutInterval = 1

    let semaphore = DispatchSemaphore(value: 0)
    let answered = Box(false)
    URLSession.shared.dataTask(with: request) { data, _, _ in
        answered.value = data.map { String(decoding: $0, as: UTF8.self).contains("\"ok\"") } ?? false
        semaphore.signal()
    }.resume()
    _ = semaphore.wait(timeout: .now() + 2)
    return answered.value
}

final class Box<T>: @unchecked Sendable {
    var value: T
    init(_ value: T) { self.value = value }
}
