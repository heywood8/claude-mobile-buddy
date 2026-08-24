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

func hookSnippet(port: Int) -> String {
    let base = "http://127.0.0.1:\(port)"
    // The hook timeout sits just above the bridge's own window, so the bridge is always the
    // one that decides to give up. Claude Code's default of ten minutes never comes into play.
    return """
    {
      "hooks": {
        "PermissionRequest": [
          { "hooks": [ { "type": "http", "url": "\(base)/permission-request", "timeout": 60 } ] }
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
    print(hookSnippet(port: port))

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
    let link = BLELink(log: log)
    let coordinator = Coordinator(link: link, log: log)

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

      cmbridge run [--port N]          run the bridge (default port 8787)
      cmbridge pair [--rotate] [--url] show the pairing QR code
      cmbridge status [--port N]       show the identity and whether the bridge is up
      cmbridge print-hook [--port N]   print the settings.json snippet to paste

    The bridge never edits ~/.claude/settings.json for you.
    """)
    exit(2)
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
