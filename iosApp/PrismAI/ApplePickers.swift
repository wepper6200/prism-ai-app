import Foundation
import UIKit
import PhotosUI
import UniformTypeIdentifiers
import PrismShared

extension AppleHost {
    func pickImages(completion: @escaping ([Data]) -> Void, failure: @escaping () -> Void) {
        DispatchQueue.main.async {
            var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 10
            let picker = PHPickerViewController(configuration: config)
            let delegate = PhotosDelegate { images in completion(images); self.pickerDelegate = nil }
            self.pickerDelegate = delegate; picker.delegate = delegate; self.present(picker)
        }
    }
    func cameraAvailable() -> Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }
    func takePhoto(completion: @escaping (Data?) -> Void, failure: @escaping () -> Void) {
        DispatchQueue.main.async {
            guard self.cameraAvailable() else { failure(); return }
            let picker = UIImagePickerController(); picker.sourceType = .camera
            let delegate = CameraDelegate { data in completion(data); self.pickerDelegate = nil }
            self.pickerDelegate = delegate; picker.delegate = delegate; self.present(picker)
        }
    }
    func clipboardImageAvailable() -> Bool { UIPasteboard.general.hasImages }
    func clipboardImage() -> Data? { UIPasteboard.general.image?.jpegData(compressionQuality: 0.9) }
    func pickDocuments(completion: @escaping ([IosPickedFile]) -> Void, failure: @escaping () -> Void) {
        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf, .plainText, UTType(filenameExtension: "md") ?? .plainText, UTType(filenameExtension: "docx") ?? .data], asCopy: true)
            picker.allowsMultipleSelection = true
            let delegate = FilesDelegate { urls in
                DispatchQueue.global(qos: .userInitiated).async {
                    var files: [IosPickedFile] = []
                    for url in urls {
                        let scoped = url.startAccessingSecurityScopedResource(); defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= 50 * 1024 * 1024, let data = try? Data(contentsOf: url) else { DispatchQueue.main.async { failure() }; continue }
                        files.append(IosPickedFile(name: url.lastPathComponent, data: data))
                    }
                    let result = files
                    DispatchQueue.main.async { completion(result); self.pickerDelegate = nil }
                }
            }
            self.pickerDelegate = delegate; picker.delegate = delegate; self.present(picker)
        }
    }
    func pickFolder(completion: @escaping (String) -> Void) {
        DispatchQueue.main.async {
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
            let delegate = FilesDelegate { urls in
                defer { self.pickerDelegate = nil }
                guard let url = urls.first, url.startAccessingSecurityScopedResource() else { return }
                guard let bookmark = try? url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil) else { url.stopAccessingSecurityScopedResource(); return }
                let location = "bookmark:" + bookmark.base64EncodedString()
                self.scopedFolders[location] = url
                completion(location)
            }
            self.pickerDelegate = delegate; picker.delegate = delegate; self.present(picker)
        }
    }
    func exportFile(data: Data, name: String, completion: @escaping (KotlinBoolean) -> Void) {
        DispatchQueue.main.async {
            do {
                let folder = self.fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                try self.fm.createDirectory(at: folder, withIntermediateDirectories: true)
                let file = folder.appendingPathComponent(URL(fileURLWithPath: name).lastPathComponent)
                try data.write(to: file)
                let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
                sheet.completionWithItemsHandler = { _, success, _, _ in completion(KotlinBoolean(value: success)); try? self.fm.removeItem(at: folder) }
                self.present(sheet)
            } catch { completion(KotlinBoolean(value: false)) }
        }
    }
}
private final class PhotosDelegate: NSObject, PHPickerViewControllerDelegate {
    let done: ([Data]) -> Void
    init(_ done: @escaping ([Data]) -> Void) { self.done = done }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        let group = DispatchGroup(), lock = NSLock()
        var values: [Int: Data] = [:]
        for (index, item) in results.enumerated() {
            group.enter()
            item.itemProvider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                if let data, data.count <= 50 * 1024 * 1024 { lock.lock(); values[index] = data; lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: .main) { self.done(values.keys.sorted().compactMap { values[$0] }) }
    }
}
private final class CameraDelegate: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    let done: (Data?) -> Void
    init(_ done: @escaping (Data?) -> Void) { self.done = done }
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true); done(nil) }
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { picker.dismiss(animated: true); done((info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.9)) }
}
private final class FilesDelegate: NSObject, UIDocumentPickerDelegate {
    let done: ([URL]) -> Void
    init(_ done: @escaping ([URL]) -> Void) { self.done = done }
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { done([]) }
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { done(urls) }
}
