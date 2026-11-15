import Foundation
import PrismShared

extension AppleHost {
    func download(url: String, destination: String, resumePath: String, progress: @escaping (KotlinLong, KotlinLong) -> KotlinUnit, completion: @escaping (String?) -> KotlinUnit) -> any IosCancellation {
        guard let address = URL(string: url), ["https", "http"].contains(address.scheme?.lowercased() ?? "") else { _ = completion("Invalid download address"); return AppleCancellation() }
        let delegate = ModelDownload(destination: destination, resumePath: resumePath, progress: progress, completion: completion)
        let session = URLSession(configuration: offlineSessionConfiguration(), delegate: delegate, delegateQueue: nil)
        let task: URLSessionDownloadTask
        if let data = try? Data(contentsOf: URL(fileURLWithPath: resumePath)) { task = session.downloadTask(withResumeData: data) }
        else { task = session.downloadTask(with: address) }
        task.resume()
        return AppleCancellation { task.cancel { data in
            if let data { try? data.write(to: URL(fileURLWithPath: resumePath), options: .atomic) }
        } }
    }
    func request(url: String, method: String, headers: [String: String], body: Data?, maxBytes: Int64, completion: @escaping (IosHttpResponse?, String?) -> KotlinUnit) -> any IosCancellation {
        guard let address = URL(string: url), ["https", "http"].contains(address.scheme?.lowercased() ?? "") else { _ = completion(nil, "Invalid web address"); return AppleCancellation() }
        var request = URLRequest(url: address); request.httpMethod = method; request.httpBody = body; request.allHTTPHeaderFields = headers; request.timeoutInterval = 20
        let delegate = BoundedResponse(limit: maxBytes, completion: completion)
        let session = URLSession(configuration: offlineSessionConfiguration(), delegate: delegate, delegateQueue: nil)
        let task = session.dataTask(with: request); task.resume()
        return AppleCancellation { task.cancel() }
    }
}
private func offlineSessionConfiguration() -> URLSessionConfiguration {
    let c = URLSessionConfiguration.ephemeral
    c.httpShouldSetCookies = false; c.httpCookieStorage = nil; c.urlCache = nil
    c.requestCachePolicy = .reloadIgnoringLocalCacheData
    c.timeoutIntervalForRequest = 30; c.timeoutIntervalForResource = 24 * 60 * 60
    return c
}
private final class ModelDownload: NSObject, URLSessionDownloadDelegate {
    let destination: String, resumePath: String
    let progress: (KotlinLong, KotlinLong) -> KotlinUnit
    let completion: (String?) -> KotlinUnit
    var storedError: String?, finished = false
    init(destination: String, resumePath: String, progress: @escaping (KotlinLong, KotlinLong) -> KotlinUnit, completion: @escaping (String?) -> KotlinUnit) { self.destination = destination; self.resumePath = resumePath; self.progress = progress; self.completion = completion }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        _ = progress(KotlinLong(value: totalBytesWritten), KotlinLong(value: max(0, totalBytesExpectedToWrite)))
        try? String(totalBytesWritten).write(toFile: resumePath + ".bytes", atomically: true, encoding: .utf8)
    }
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let response = downloadTask.response as? HTTPURLResponse, (200..<300).contains(response.statusCode) else { storedError = "HTTP download failed"; return }
        do {
            if FileManager.default.fileExists(atPath: destination) { try FileManager.default.removeItem(atPath: destination) }
            try FileManager.default.moveItem(at: location, to: URL(fileURLWithPath: destination)); finished = true
            try? FileManager.default.removeItem(atPath: resumePath); try? FileManager.default.removeItem(atPath: resumePath + ".bytes")
        } catch { storedError = error.localizedDescription }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let resume = (error as NSError?)?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data { try? resume.write(to: URL(fileURLWithPath: resumePath), options: .atomic) }
        if (error as NSError?)?.code == NSURLErrorCannotResume { try? FileManager.default.removeItem(atPath: resumePath); try? FileManager.default.removeItem(atPath: resumePath + ".bytes") }
        _ = completion(storedError ?? error?.localizedDescription ?? (finished ? nil : "Download incomplete"))
        session.finishTasksAndInvalidate()
    }
}
private final class BoundedResponse: NSObject, URLSessionDataDelegate {
    let limit: Int64, completion: (IosHttpResponse?, String?) -> KotlinUnit
    var bytes = Data(), response: HTTPURLResponse?, storedError: String?, redirects = 0
    init(limit: Int64, completion: @escaping (IosHttpResponse?, String?) -> KotlinUnit) { self.limit = max(0, limit); self.completion = completion }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        self.response = response as? HTTPURLResponse
        if response.expectedContentLength > limit { storedError = "Response is too large"; completionHandler(.cancel) } else { completionHandler(.allow) }
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if Int64(bytes.count) + Int64(data.count) > limit { storedError = "Response is too large"; dataTask.cancel() } else { bytes.append(data) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        redirects += 1
        guard redirects <= 5, ["http", "https"].contains(request.url?.scheme?.lowercased() ?? "") else { storedError = "Invalid redirect"; completionHandler(nil); return }
        completionHandler(request)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = storedError ?? error?.localizedDescription { _ = completion(nil, error) }
        else if let response {
            let headers = Dictionary(response.allHeaderFields.compactMap { key, value -> (String, String)? in guard let key = key as? String, let value = value as? String else { return nil }; return (key, value) }, uniquingKeysWith: { _, last in last })
            _ = completion(IosHttpResponse(status: Int32(response.statusCode), data: bytes, headers: headers), nil)
        } else { _ = completion(nil, "No HTTP response") }
        session.finishTasksAndInvalidate()
    }
}
