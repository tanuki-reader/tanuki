import Foundation
import Nuke
import Shared

/// Nuke's network layer for **reader pages**: a source page comes from Kotlin (`ImageBridge.loadPageData`,
/// the reader's own Coil loader over the Cloudflare-aware Ktor client and `page_cache`); a downloaded page
/// is a file on disk, read here. Never `URLSession` for a remote page, which would skip the Cloudflare solve
/// and the source's headers.
final class PageDataLoader: DataLoading {
    func loadData(
        with request: URLRequest,
        didReceiveData: @escaping @Sendable (Data, URLResponse) -> Void,
        completion: @escaping @Sendable (Error?) -> Void
    ) -> any Cancellable {
        let task = Task {
            do {
                guard let url = request.url else { return completion(ImageUnavailable()) }
                let data: Data?
                if url.isFileURL {
                    data = try? Data(contentsOf: url, options: .mappedIfSafe)
                } else {
                    data = try await ImageBridgeKt.loadPageData(url: url.absoluteString, headers: request.allHTTPHeaderFields ?? [:])
                }
                guard let data else { return completion(ImageUnavailable()) }
                didReceiveData(data, URLResponse(url: url, mimeType: nil, expectedContentLength: data.count, textEncodingName: nil))
                completion(nil)
            } catch {
                completion(error)
            }
        }
        return PageTaskCancellable(task: task)
    }
}

private struct PageTaskCancellable: Cancellable {
    let task: Task<Void, Never>

    func cancel() { task.cancel() }
}
