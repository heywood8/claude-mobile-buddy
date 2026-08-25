import Foundation
import CoreImage
import ImageIO

/// Renders a QR code as text, so pairing needs no second screen and nothing typed.
///
/// CoreImage is a system framework, which keeps this dependency-free.
///
/// One module per terminal row, two characters wide, rather than the half-block trick that
/// packs two module rows into one row. Half blocks are half the size, but they only survive a
/// terminal whose line height matches its font exactly. Measured on a real one: line height
/// 29.9px, `█` inked for 25px, leaving a 5px seam. Against a 15px half-block module that seam
/// is a third of the module and lands where a decoder samples; against a 30px full-block
/// module it is 17% and lands on the boundary, where nothing reads.
enum QRCode {
    /// Characters per module. A terminal cell is about twice as tall as it is wide, so two
    /// of them make a module roughly square.
    static let cellsPerModule = 2

    /// Columns the rendered code needs. A code that wraps is unreadable, and wrapping is
    /// invisible until a camera fails to see anything.
    static func columns(for text: String) -> Int? {
        guard let modules = matrix(for: text) else { return nil }
        return (modules.count + quietZone * 2) * cellsPerModule
    }

    /// Modules of white space around the code. The spec asks for four; scanners get unhappy
    /// with less, especially against a dark terminal.
    static let quietZone = 4

    static func matrix(for text: String) -> [[Bool]]? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        // Medium recovery. The payload still renders about fifty columns wide at this level,
        // so there is no reason to trade error correction away for a size nobody needs.
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let image = filter.outputImage else { return nil }

        let width = Int(image.extent.width)
        let height = Int(image.extent.height)
        guard width > 0, height > 0 else { return nil }

        var pixels = [UInt8](repeating: 0, count: width * height)
        guard let colorSpace = CGColorSpace(name: CGColorSpace.linearGray),
              let context = CGContext(
                data: &pixels,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.none.rawValue),
              let cgImage = CIContext().createCGImage(image, from: image.extent)
        else { return nil }

        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))

        // `CGContext.draw` already lands the top of the image in the first row of the buffer.
        // Flipping here as well turns the code upside down, which a scanner still reads but
        // the finder-pattern test in QRCodeTests does not.
        let raw = (0..<height).map { row in
            (0..<width).map { column in
                pixels[row * width + column] < 128
            }
        }
        return trimBorder(raw)
    }

    /// CIQRCodeGenerator hands back the code wrapped in a one-module white border. Strip it so
    /// `quietZone` below is the only padding, rather than silently one module wider.
    ///
    /// Safe to apply generically: the outermost row and column of a real QR always cut through
    /// a finder pattern, so they are never entirely blank.
    private static func trimBorder(_ modules: [[Bool]]) -> [[Bool]] {
        var result = modules
        while result.count > 7,
              result.first?.allSatisfy({ !$0 }) == true,
              result.last?.allSatisfy({ !$0 }) == true,
              result.allSatisfy({ $0.first == false && $0.last == false }) {
            result = result.dropFirst().dropLast().map { Array($0.dropFirst().dropLast()) }
        }
        return result
    }

    /// The same code as a PNG, for terminals whose line height does not match their font.
    ///
    /// A terminal render is at the mercy of glyph metrics: a line height a few pixels taller
    /// than the block glyph leaves a seam on every row, and a scanner looking for the
    /// 1:1:3:1:1 run ratio inside a finder pattern never sees it. Measured on a real terminal:
    /// 5px of seam against a 30px module, enough to make a structurally perfect code
    /// undetectable. An image has no glyphs and no line height.
    static func pngData(for text: String, scale: Int = 10) -> Data? {
        guard let modules = matrix(for: text) else { return nil }
        let side = (modules.count + quietZone * 2) * scale

        guard let colorSpace = CGColorSpace(name: CGColorSpace.linearGray),
              let context = CGContext(
                data: nil,
                width: side,
                height: side,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.none.rawValue)
        else { return nil }

        context.setFillColor(gray: 1, alpha: 1)
        context.fill(CGRect(x: 0, y: 0, width: side, height: side))
        context.setFillColor(gray: 0, alpha: 1)
        for (row, line) in modules.enumerated() {
            for (column, isDark) in line.enumerated() where isDark {
                context.fill(CGRect(
                    x: (column + quietZone) * scale,
                    // CoreGraphics counts up from the bottom; the matrix counts down from
                    // the top.
                    y: (modules.count - 1 - row + quietZone) * scale,
                    width: scale,
                    height: scale))
            }
        }

        guard let image = context.makeImage(),
              let data = CFDataCreateMutable(nil, 0),
              let destination = CGImageDestinationCreateWithData(data, "public.png" as CFString, 1, nil)
        else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    /// Black on white regardless of the terminal's own colours. A QR rendered light-on-dark is
    /// inverted, and while many scanners cope, some quietly do not.
    static func render(_ text: String) -> String? {
        guard let modules = matrix(for: text) else { return nil }
        let width = modules[0].count + quietZone * 2
        let padded =
            Array(repeating: [Bool](repeating: false, count: width), count: quietZone)
            + modules.map {
                [Bool](repeating: false, count: quietZone)
                    + $0
                    + [Bool](repeating: false, count: quietZone)
            }
            + Array(repeating: [Bool](repeating: false, count: width), count: quietZone)

        let reset = "\u{1B}[0m"
        let blackOnWhite = "\u{1B}[30;47m"

        // Two characters per module keeps it roughly square: a terminal cell is about twice
        // as tall as it is wide.
        return padded.map { row in
            blackOnWhite + row.map { $0 ? "\u{2588}\u{2588}" : "  " }.joined() + reset
        }.joined(separator: "\n")
    }
}
