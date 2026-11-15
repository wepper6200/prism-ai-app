import Foundation
import UIKit
import CryptoKit
import PDFKit
import ImageIO
import PrismShared
import os

final class AppleCancellation: IosCancellation {
    private let lock = NSLock()
    private var action: (() -> Void)?
    init(_ action: @escaping () -> Void = {}) { self.action = action }
    func cancel() { lock.lock(); let action = self.action; self.action = nil; lock.unlock(); action?() }
}

final class AppleHost: NSObject, IosHost {
    let fm = FileManager.default
    var pickerDelegate: AnyObject?
    var scopedFolders: [String: URL] = [:]
    private let directory: URL = {
        var url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("PrismAI", isDirectory: true)
        try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }()
    func setSystemAppearance(dark: Bool) {
        DispatchQueue.main.async {
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.forEach { $0.overrideUserInterfaceStyle = dark ? .dark : .light }
        }
    }
    func dataDirectory() -> String { directory.path }
    func totalMemory() -> Int64 { Int64(ProcessInfo.processInfo.physicalMemory) }
    func availableMemory() -> Int64 { Int64(os_proc_available_memory()) }
    func processorCount() -> Int32 { Int32(ProcessInfo.processInfo.activeProcessorCount) }
    func freeSpace() -> Int64 { (try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage) ?? 0 }
    func preference(key: String) -> String? { UserDefaults.standard.string(forKey: key) }
    func setPreference(key: String, value: String) { UserDefaults.standard.set(value, forKey: key) }
    func exists(path: String) -> Bool { fm.fileExists(atPath: path) }
    func size(path: String) -> Int64 { (try? fm.attributesOfItem(atPath: path)[.size] as? NSNumber)?.int64Value ?? 0 }
    func modifiedMillis(path: String) -> Int64 { Int64(((try? fm.attributesOfItem(atPath: path)[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0) * 1000) }
    func children(path: String) -> [String] { (try? fm.contentsOfDirectory(atPath: path)) ?? [] }
    func isDirectory(path: String) -> Bool { var dir: ObjCBool = false; return fm.fileExists(atPath: path, isDirectory: &dir) && dir.boolValue }
    func canonicalPath(path: String) -> String { URL(fileURLWithPath: path).standardizedFileURL.resolvingSymlinksInPath().path }
    func resolveFolder(location: String) -> String? {
        if let url = scopedFolders[location] { return url.path }
        guard location.hasPrefix("bookmark:"), let data = Data(base64Encoded: String(location.dropFirst(9))) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale), url.startAccessingSecurityScopedResource() else { return nil }
        scopedFolders[location] = url
        return url.path
    }
    func read(path: String) -> Data? { try? Data(contentsOf: URL(fileURLWithPath: path)) }
    func write(path: String, data: Data) -> Bool { do { try data.write(to: URL(fileURLWithPath: path), options: .atomic); return true } catch { return false } }
    func makeDirectory(path: String) -> Bool { do { try fm.createDirectory(atPath: path, withIntermediateDirectories: true); return true } catch { return false } }
    func remove(path: String) -> Bool { if !exists(path: path) { return true }; do { try fm.removeItem(atPath: path); return true } catch { return false } }
    func move(from: String, to: String) -> Bool {
        do {
            if fm.fileExists(atPath: to) { _ = try fm.replaceItemAt(URL(fileURLWithPath: to), withItemAt: URL(fileURLWithPath: from)) }
            else { try fm.moveItem(atPath: from, toPath: to) }
            return true
        } catch { return false }
    }
    func sha256(path: String) -> String? {
        guard let file = FileHandle(forReadingAtPath: path) else { return nil }
        defer { try? file.close() }
        do {
            var hash = SHA256()
            while let chunk = try file.read(upToCount: 1024 * 1024), !chunk.isEmpty { hash.update(data: chunk) }
            return hash.finalize().map { String(format: "%02x", $0) }.joined()
        } catch { return nil }
    }
    func unpackArchive(path: String, destination: String) -> Bool { AppleArchives.shared.unpack(path: path, destination: destination) }
    func encodeImage(data: Data, maxSide: Int32) -> IosEncodedImage? {
        guard maxSide > 0, let source = CGImageSourceCreateWithData(data as CFData, nil),
            let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: maxSide, kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { return nil }
        let image = UIImage(cgImage: thumbnail)
        let scale = min(1, CGFloat(maxSide) / max(image.size.width, image.size.height))
        let width = max(1, Int(image.size.width * scale)), height = max(1, Int(image.size.height * scale))
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        let normalized = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
            image.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        guard let jpeg = normalized.jpegData(compressionQuality: 0.88), let cg = normalized.cgImage else { return nil }
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &rgba, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        var rgb = Data(count: width * height * 3)
        rgb.withUnsafeMutableBytes { (out: UnsafeMutableRawBufferPointer) in
            for i in 0..<(width * height) { out[i * 3] = rgba[i * 4]; out[i * 3 + 1] = rgba[i * 4 + 1]; out[i * 3 + 2] = rgba[i * 4 + 2] }
        }
        return IosEncodedImage(jpeg: jpeg, rgb: rgb, width: Int32(width), height: Int32(height))
    }
    func extractDocument(data: Data, format: String) -> IosDocumentResult {
        if format == "PDF" {
            guard let pdf = PDFDocument(data: data), !pdf.isLocked else { return IosDocumentResult(pages: [], error: "PDF is unreadable or password protected") }
            return IosDocumentResult(pages: (0..<pdf.pageCount).compactMap { i in pdf.page(at: i)?.string.map { IosTextPage(page: Int32(i + 1), text: $0) } }, error: nil)
        }
        if format == "DOCX" {
            guard let xml = AppleArchives.shared.documentXml(data: data) else { return IosDocumentResult(pages: [], error: "Cannot read DOCX") }
            let delegate = DocumentXmlReader()
            let parser = XMLParser(data: xml); parser.shouldResolveExternalEntities = false; parser.delegate = delegate
            guard parser.parse() else { return IosDocumentResult(pages: [], error: "Invalid DOCX XML") }
            return IosDocumentResult(pages: [IosTextPage(page: 0, text: delegate.text)], error: nil)
        }
        let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16) ?? String(data: data, encoding: .windowsCP1251)
        return IosDocumentResult(pages: text.map { [IosTextPage(page: 0, text: $0)] } ?? [], error: text == nil ? "Unsupported text encoding" : nil)
    }
    func present(_ controller: UIViewController) {
        DispatchQueue.main.async {
            guard var top = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).flatMap({ $0.windows }).first(where: { $0.isKeyWindow })?.rootViewController else { return }
            while let presented = top.presentedViewController { top = presented }
            if let popover = controller.popoverPresentationController { popover.sourceView = top.view; popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1) }
            top.present(controller, animated: true)
        }
    }
}
private final class DocumentXmlReader: NSObject, XMLParserDelegate {
    var text = "", inText = false
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) { if elementName == "w:t" { inText = true }; if elementName == "w:tab" { text += "\t" }; if elementName == "w:br" { text += "\n" } }
    func parser(_ parser: XMLParser, foundCharacters string: String) { if inText { text += string } }
    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) { if elementName == "w:t" { inText = false }; if elementName == "w:p" { text += "\n" } }
}
