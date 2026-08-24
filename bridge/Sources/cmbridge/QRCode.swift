import Foundation
import CoreImage

/// Renders a QR code as text, so pairing needs no second screen and nothing typed.
///
/// CoreImage is a system framework, which keeps this dependency-free. The code is drawn with
/// half-block characters so one terminal row carries two module rows — a full-block rendering
/// comes out twice as tall as it needs to be and stops fitting in a window.
enum QRCode {
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

        var lines: [String] = []
        var row = 0
        while row < padded.count {
            let top = padded[row]
            let bottom = row + 1 < padded.count
                ? padded[row + 1]
                : [Bool](repeating: false, count: width)
            var line = blackOnWhite
            for column in 0..<width {
                switch (top[column], bottom[column]) {
                case (true, true): line += "\u{2588}"
                case (true, false): line += "\u{2580}"
                case (false, true): line += "\u{2584}"
                case (false, false): line += " "
                }
            }
            lines.append(line + reset)
            row += 2
        }
        return lines.joined(separator: "\n")
    }
}
