import Testing
import Foundation
@testable import cmbridge

@Suite("Settings merge")
struct HookInstallerTests {

    // MARK: - The parser has to be lossless before the merge can be trusted

    @Test("round-trips a file without changing it semantically")
    func roundTrips() throws {
        let source = """
        {
          "attribution": { "commit": false },
          "permissions": { "allow": ["Bash(ls:*)"] },
          "hooks": {
            "PreToolUse": [
              { "matcher": "Bash", "hooks": [{ "type": "command", "command": "true" }] }
            ]
          }
        }
        """
        let parsed = try JSONValue.parse(source)
        let again = try JSONValue.parse(parsed.serialized())
        #expect(parsed == again)
    }

    @Test("keeps key order")
    func keepsKeyOrder() throws {
        // Order is what makes the diff readable. Reshuffling keys would bury a three-line
        // change in a rewrite of the whole file.
        let source = #"{"zebra":1,"alpha":2,"middle":3}"#
        let rendered = try JSONValue.parse(source).serialized()
        let keys = ["zebra", "alpha", "middle"].map { rendered.range(of: "\"\($0)\"")!.lowerBound }
        #expect(keys == keys.sorted())
    }

    @Test("keeps numbers as written")
    func keepsNumbers() throws {
        // 1860 must not come back as 1860.0, and 1.10 must not lose its zero.
        let rendered = try JSONValue.parse(#"{"a":1860,"b":1.10,"c":-3e5}"#).serialized()
        #expect(rendered.contains("1860"))
        #expect(!rendered.contains("1860.0"))
        #expect(rendered.contains("1.10"))
        #expect(rendered.contains("-3e5"))
    }

    // MARK: - The merge

    @Test("appends to an existing event instead of replacing it")
    func appendsRatherThanReplaces() throws {
        let path = try write("""
        {
          "hooks": {
            "PostToolUse": [
              { "matcher": "Bash", "hooks": [{ "type": "command", "command": "mine" }] }
            ]
          }
        }
        """)
        defer { try? FileManager.default.removeItem(at: path) }

        let plan = try HookInstaller.plan(path: path, port: 8787, window: 1800)
        let root = try JSONValue.parse(plan.after)
        let entries = try #require(root["hooks"]?["PostToolUse"]?.arrayValue)

        // The one that was already there is still there, untouched.
        #expect(entries.count == 2)
        #expect(entries[0]["hooks"]?.arrayValue?.first?["command"]?.stringValue == "mine")
        #expect(HookInstaller.isOurs(entries[1], base: HookInstaller.base(port: 8787)))
    }

    @Test("leaves unrelated settings alone")
    func leavesOtherSettingsAlone() throws {
        let path = try write(#"{"statusLine":{"type":"command"},"permissions":{"allow":["Bash(ls:*)"]}}"#)
        defer { try? FileManager.default.removeItem(at: path) }

        let root = try JSONValue.parse(
            try HookInstaller.plan(path: path, port: 8787, window: 1800).after)
        #expect(root["statusLine"]?["type"]?.stringValue == "command")
        #expect(root["permissions"]?["allow"]?.arrayValue?.first?.stringValue == "Bash(ls:*)")
    }

    @Test("is idempotent")
    func isIdempotent() throws {
        let path = try write("{}")
        defer { try? FileManager.default.removeItem(at: path) }

        let first = try HookInstaller.plan(path: path, port: 8787, window: 1800)
        try HookInstaller.apply(first)
        defer { try? FileManager.default.removeItem(at: path.appendingPathExtension("bak")) }

        let second = try HookInstaller.plan(path: path, port: 8787, window: 1800)
        #expect(second.isNoop)
    }

    @Test("updates its own entry rather than leaving a stale one")
    func replacesItsOwnEntry() throws {
        let path = try write("{}")
        defer { try? FileManager.default.removeItem(at: path) }

        try HookInstaller.apply(try HookInstaller.plan(path: path, port: 8787, window: 1800))
        defer { try? FileManager.default.removeItem(at: path.appendingPathExtension("bak")) }

        // A second install with a longer window must not leave the old hook behind — it would
        // still be answering, with the old timeout, and nothing would say so.
        let widened = try HookInstaller.plan(path: path, port: 8787, window: 3600)
        let entries = try #require(try JSONValue.parse(widened.after)["hooks"]?["PermissionRequest"]?.arrayValue)
        #expect(entries.count == 1)

        let timeout = entries[0]["hooks"]?.arrayValue?.first?["timeout"]
        #expect(timeout == .number("3660"))
    }

    @Test("derives the hook timeout from the window")
    func timeoutFollowsWindow() throws {
        let entry = HookInstaller.entry(for: "PermissionRequest", port: 8787, window: 1800)
        let handler = try #require(entry["hooks"]?.arrayValue?.first)
        #expect(handler["timeout"] == .number("1860"))
        #expect(handler["url"]?.stringValue == "http://127.0.0.1:8787/permission-request")
    }

    @Test("creates the block when there is no settings file at all")
    func handlesMissingFile() throws {
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("cmb-absent-\(UUID().uuidString).json")
        let plan = try HookInstaller.plan(path: path, port: 8787, window: 1800)
        let root = try JSONValue.parse(plan.after)
        #expect(root["hooks"]?["PermissionRequest"] != nil)
    }

    // MARK: - The diff

    @Test("shows only the lines that changed")
    func diffIsMinimal() {
        let before = "a\nb\nc\nd\ne\nf\ng\nh"
        let after = "a\nb\nc\nd\nNEW\ne\nf\ng\nh"
        let diff = LineDiff.render(before: before, after: after, context: 1)
        #expect(diff.contains("+ NEW"))
        // A diff that reprints the whole file is not something anyone reads.
        #expect(!diff.contains("  a"))
    }

    // MARK: - Helpers

    private func write(_ contents: String) throws -> URL {
        let path = FileManager.default.temporaryDirectory
            .appendingPathComponent("cmb-settings-\(UUID().uuidString).json")
        try contents.write(to: path, atomically: true, encoding: .utf8)
        return path
    }
}
