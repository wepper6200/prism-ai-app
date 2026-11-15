import AppKit
import Foundation
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
final class Logo: NSObject, XMLParserDelegate {
    var paths: [[String: String]] = []
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) { if name == "path" { paths.append(attributes) } }
}
let logo = Logo(), parser = XMLParser(contentsOf: root.appendingPathComponent("shared/src/commonMain/composeResources/drawable/prism_logo.xml"))!
parser.delegate = logo
precondition(parser.parse())
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 1024, pixelsHigh: 1024, bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
let context = NSGraphicsContext(bitmapImageRep: bitmap)!
NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = context
NSColor(red: 0.043, green: 0.055, blue: 0.075, alpha: 1).setFill(); NSRect(x: 0, y: 0, width: 1024, height: 1024).fill()
let cg = context.cgContext; cg.translateBy(x: 128, y: 896); cg.scaleBy(x: 16, y: -16)
func color(_ hex: String) -> CGColor {
    let value = UInt32(hex.suffix(6), radix: 16)!
    return CGColor(red: CGFloat((value >> 16) & 255) / 255, green: CGFloat((value >> 8) & 255) / 255, blue: CGFloat(value & 255) / 255, alpha: 1)
}
let regex = try! NSRegularExpression(pattern: "[MLZ]|-?[0-9]+(?:\\.[0-9]+)?")
for p in logo.paths {
    let source = p["android:pathData"]!
    let tokens = regex.matches(in: source, range: NSRange(source.startIndex..., in: source)).map { String(source[Range($0.range, in: source)!]) }
    let path = CGMutablePath(); var i = 0
    while i < tokens.count {
        let command = tokens[i]; i += 1
        if command == "Z" { path.closeSubpath(); continue }
        let point = CGPoint(x: Double(tokens[i])!, y: Double(tokens[i + 1])!); i += 2
        if command == "M" { path.move(to: point) } else { path.addLine(to: point) }
    }
    if let fill = p["android:fillColor"] { cg.addPath(path); cg.setFillColor(color(fill)); cg.fillPath() }
    if let stroke = p["android:strokeColor"] { cg.addPath(path); cg.setStrokeColor(color(stroke)); cg.setLineWidth(CGFloat(Double(p["android:strokeWidth"]!)!)); cg.setLineCap(.round); cg.strokePath() }
}
NSGraphicsContext.restoreGraphicsState()
let output = root.appendingPathComponent("iosApp/PrismAI/Assets.xcassets/AppIcon.appiconset", isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
try bitmap.representation(using: .png, properties: [:])!.write(to: output.appendingPathComponent("AppIcon.png"))
let contents: [String: Any] = ["images": [["filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"]], "info": ["author": "Prism AI", "version": 1]]
try JSONSerialization.data(withJSONObject: contents).write(to: output.appendingPathComponent("Contents.json"))
