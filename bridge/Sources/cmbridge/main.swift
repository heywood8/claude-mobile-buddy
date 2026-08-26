import Foundation

let log = Logger()

/// Signal sources, held for the life of the process on purpose.
///
/// A `DispatchSourceSignal` stops firing when it is released, and the recipe for using one
/// sets the signal's default disposition to ignore first — so a source that goes out of scope
/// does not restore the old behaviour, it removes it. The bridge would then ignore SIGTERM
/// outright and only die to SIGKILL, with every waiting hook hung up on. Measured, not
/// theorised: the first version of this held them in a local.
var shutdownSources: [DispatchSourceSignal] = []

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
let usePNG = takeFlag("--png")
let settingsPath = takeValue("--settings").map { URL(fileURLWithPath: ($0 as NSString).expandingTildeInPath) }
let port = takeValue("--port").flatMap(Int.init) ?? 8787
let window = takeValue("--window").flatMap(TimeInterval.init) ?? Coordinator.defaultWindow
let skippedTools: Set<String> = takeValue("--skip-tools")
    .map { Set($0.split(separator: ",").map(String.init)) } ?? Coordinator.defaultSkippedTools

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
        if usePNG {
            showAsImage(code)
            break
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

case "install-hook":
    do {
        let plan = try HookInstaller.plan(
            path: settingsPath ?? HookInstaller.defaultPath, port: port, window: window)
        if plan.isNoop {
            print("Already installed and up to date: \(plan.path.path)")
            break
        }
        let counts = LineDiff.counts(before: plan.before, after: plan.after)
        print("Changes to \(plan.path.path): +\(counts.added) −\(counts.removed)")
        print()
        print(LineDiff.render(before: plan.before, after: plan.after))
        print()
        if plan.reformats {
            print("Note: the file is also reindented, since it is rewritten from a parse.")
            print("Key order is preserved; only whitespace moves.")
        }
        print("A copy of the current file is kept as settings.json.bak.")
        print()
        print("Apply? [y/N] ", terminator: "")
        guard let answer = readLine()?.lowercased(), answer == "y" || answer == "yes" else {
            print("Nothing written.")
            break
        }
        try HookInstaller.apply(plan)
        print("Written. Start a new claude session — the current one read its config at launch.")
    } catch {
        log.error("could not install the hooks: \(error)")
        exit(1)
    }

case "print-agent":
    // Printed, not installed: loading an agent writes into ~/Library/LaunchAgents and
    // arranges for something to run at every login. That is the user's call, the same way
    // the hook block is.
    let executable = Bundle.main.executableURL?.path
        ?? CommandLine.arguments[0]
    // launchd stores this path verbatim and never looks at it again. Printed from a build
    // directory it names something `make clean` deletes and moving the checkout renames, and
    // the failure is quiet: launchd respawns a missing file forever and the only trace is a
    // log nobody has a reason to open.
    if executable.contains("/.build/") || executable.contains("/dist/") {
        log.error("""
        this is the copy at \(executable) — run `make install` and print the agent from the \
        installed bundle, or the agent breaks the first time you move or clean the checkout
        """)
    }
    print("""
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0">
    <dict>
    \t<key>Label</key>
    \t<string>dev.heywood8.cmbridge</string>
    \t<key>ProgramArguments</key>
    \t<array>
    \t\t<string>\(executable)</string>
    \t\t<string>run</string>
    \t\t<string>--port</string>
    \t\t<string>\(port)</string>
    \t\t<string>--window</string>
    \t\t<string>\(Int(window))</string>
    \t</array>
    \t<key>RunAtLoad</key>
    \t<true/>
    \t<key>KeepAlive</key>
    \t<true/>
    \t<key>StandardErrorPath</key>
    \t<string>\(NSHomeDirectory())/Library/Logs/cmbridge.log</string>
    \t<key>StandardOutPath</key>
    \t<string>\(NSHomeDirectory())/Library/Logs/cmbridge.log</string>
    </dict>
    </plist>
    """)

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
    let coordinator = Coordinator(
        link: link, log: log, window: window, skippedTools: skippedTools)

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

    // launchd stops an agent with SIGTERM, and every reinstall does exactly that. Dying with
    // requests in flight leaves the hooks holding them to discover a closed socket, which is
    // not an answer — so the queue is released first and each caller gets the ordinary "no
    // decision" it would have got from an expiring window.
    shutdownSources = [SIGTERM, SIGINT].map { number -> DispatchSourceSignal in
        // The dispatch source only ever fires with the default disposition out of the way.
        signal(number, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: number, queue: .global())
        source.setEventHandler {
            log.info("shutting down")
            Task {
                await coordinator.drain(reason: "bridge shutting down")
                if let bye = try? LineCodec.payload(Bye(reason: "shutdown")) {
                    link.send(bye)
                }
                // Long enough for those responses to be written and the bye to reach the
                // radio, short enough that launchd does not run out of patience and follow
                // with SIGKILL.
                try? await Task.sleep(nanoseconds: 500_000_000)
                exit(0)
            }
        }
        source.resume()
        return source
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

      cmbridge run [--port N] [--window SECONDS] [--skip-tools A,B]
                                       run the bridge (port 8787, window 30 min;
                                       AskUserQuestion and ExitPlanMode stay in the terminal)
      cmbridge pair [--png] [--rotate] [--url]
                                       show the pairing code; --png opens an image
                                       instead of drawing it in the terminal
      cmbridge status [--port N]       show the identity and whether the bridge is up
      cmbridge install-hook [--port N] [--window SECONDS] [--settings PATH]
                                       merge the hooks into ~/.claude/settings.json,
                                       showing the diff and asking first
      cmbridge print-hook [--port N] [--window SECONDS]
                                       print the snippet instead of merging it
      cmbridge print-agent [--port N] [--window SECONDS]
                                       print the LaunchAgent plist to install

    The bridge edits neither ~/.claude/settings.json nor ~/Library/LaunchAgents.
    """)
    exit(2)
}

/// Writes the pairing code to an image and opens it, then removes it.
///
/// The file carries the key, so it lands in a private temporary directory at 0600 and is
/// deleted as soon as you say you are done. That is better hygiene than the terminal render
/// it replaces: a QR in scrollback is the same secret, and scrollback outlives the moment.
func showAsImage(_ code: PairingCode) {
    guard let png = QRCode.pngData(for: code.url) else {
        log.error("could not render the QR code")
        exit(1)
    }
    let url = FileManager.default.temporaryDirectory
        .appendingPathComponent("cmb-pair-\(UUID().uuidString.prefix(8)).png")
    guard FileManager.default.createFile(
        atPath: url.path, contents: png, attributes: [.posixPermissions: 0o600])
    else {
        log.error("could not write \(url.path)")
        exit(1)
    }

    let open = Process()
    open.executableURL = URL(fileURLWithPath: "/usr/bin/open")
    open.arguments = [url.path]
    try? open.run()

    print()
    print("Opened the pairing code as an image. Scan it in Claude Buddy on your phone.")
    print("Host: \(code.hostName)  ·  id \(code.hostID.prefix(8))…")
    print()
    print("Press Enter once it has been scanned — the file is deleted then.")
    _ = readLine()
    try? FileManager.default.removeItem(at: url)
    print("Deleted \(url.lastPathComponent).")
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
