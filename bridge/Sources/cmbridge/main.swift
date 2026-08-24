import Foundation

let log = Logger()

var arguments = Array(CommandLine.arguments.dropFirst())
var port = 8787
if let index = arguments.firstIndex(of: "--port"), index + 1 < arguments.count,
   let value = Int(arguments[index + 1]) {
    port = value
    arguments.removeSubrange(index...(index + 1))
}

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
      cmbridge print-hook [--port N]   print the settings.json snippet to paste

    The bridge never edits ~/.claude/settings.json for you.
    """)
    exit(2)
}
