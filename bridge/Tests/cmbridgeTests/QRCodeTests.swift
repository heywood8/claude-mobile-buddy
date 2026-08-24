import Testing
import Foundation
@testable import cmbridge

@Suite("QR rendering")
struct QRCodeTests {

    @Test("produces a square matrix")
    func producesSquareMatrix() throws {
        let modules = try #require(QRCode.matrix(for: "cmb://pair?h=abc&k=def&n=Test"))
        #expect(modules.count > 20)
        for row in modules {
            #expect(row.count == modules.count)
        }
    }

    @Test("places a finder pattern in each of the three corners")
    func placesFinderPatterns() throws {
        // A QR code carries a 7x7 finder in three corners. If the pixel readback is upside
        // down, mirrored, or thresholded wrongly, this is what notices.
        let modules = try #require(QRCode.matrix(for: "cmb://pair?h=abc&k=def&n=Test"))
        let size = modules.count

        #expect(isFinder(modules, atRow: 0, column: 0))
        #expect(isFinder(modules, atRow: 0, column: size - 7))
        #expect(isFinder(modules, atRow: size - 7, column: 0))

        // The fourth corner holds an alignment pattern at most, never a finder.
        #expect(!isFinder(modules, atRow: size - 7, column: size - 7))
    }

    @Test("renders half-block rows with a quiet zone")
    func rendersHalfBlocks() throws {
        let rendered = try #require(QRCode.render("cmb://pair?h=abc&k=def&n=Test"))
        let lines = rendered.split(separator: "\n").map(String.init)
        let modules = try #require(QRCode.matrix(for: "cmb://pair?h=abc&k=def&n=Test"))

        // Two module rows per terminal row, plus the quiet zone above and below.
        let paddedRows = modules.count + QRCode.quietZone * 2
        #expect(lines.count == (paddedRows + 1) / 2)

        // The first row is entirely quiet zone: no ink anywhere in it.
        let first = try #require(lines.first)
        #expect(!first.contains("\u{2588}"))
        #expect(!first.contains("\u{2580}"))
        #expect(!first.contains("\u{2584}"))

        // Somewhere in the middle there has to be ink, or we are rendering a blank rectangle.
        #expect(lines.contains { $0.contains("\u{2588}") })
    }

    @Test("fits an eighty-column terminal")
    func fitsStandardTerminal() throws {
        // A pairing code that wraps is a pairing code nothing can scan, and eighty columns is
        // still what a freshly opened Terminal window gives you.
        let payload = PairingCode(
            hostID: "0123456789abcdef0123456789abcdef",
            key: Data(repeating: 0xAB, count: 32),
            hostName: "MSK-JDW962F4JG").url

        let modules = try #require(QRCode.matrix(for: payload))
        let columns = modules.count + QRCode.quietZone * 2
        #expect(columns <= 80, "payload of \(payload.count) chars rendered \(modules.count) modules")
    }

    @Test("renders dark on light rather than inverted")
    func rendersDarkOnLight() throws {
        let rendered = try #require(QRCode.render("cmb://pair?h=abc&k=def&n=Test"))
        // Black foreground on a white background, set explicitly so the terminal's own theme
        // cannot invert the code under a scanner that will not cope with that.
        #expect(rendered.contains("\u{1B}[30;47m"))
        #expect(rendered.hasSuffix("\u{1B}[0m"))
    }

    // MARK: - Helpers

    /// A finder pattern is a filled 7x7 border with a filled 3x3 core and a clear ring between.
    private func isFinder(_ modules: [[Bool]], atRow row: Int, column: Int) -> Bool {
        guard row >= 0, column >= 0,
              row + 7 <= modules.count, column + 7 <= modules.count else { return false }
        for y in 0..<7 {
            for x in 0..<7 {
                let onBorder = y == 0 || y == 6 || x == 0 || x == 6
                let inCore = (2...4).contains(y) && (2...4).contains(x)
                let expected = onBorder || inCore
                if modules[row + y][column + x] != expected { return false }
            }
        }
        return true
    }
}
