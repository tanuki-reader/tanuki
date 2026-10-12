import Foundation
import Nuke
import Shared

/// Nuke's network layer, replaced by the app's own: every byte Nuke loads comes from Kotlin
/// (`ImageBridge.kt` → Coil → the shared Ktor client).
///
/// That's the only way a cover from a protected host loads. The Ktor client carries the solved
/// `cf_clearance` and the app's one User-Agent, and its interceptor starts the Cloudflare solve. A
/// `URLSession` fetch would just get a 403. Coil also owns the disk cache (`cover_cache`), so Nuke keeps
/// only its memory cache. See `ImagePipeline.tanuki`.
///
/// The request's headers (a source's Referer, say) travel with the URL. Cancelling the load cancels the
/// Swift task, and SKIE carries that into the Kotlin coroutine.
final class KotlinDataLoader: DataLoading {
    func loadData(
        with request: URLRequest,
        didReceiveData: @escaping @Sendable (Data, URLResponse) -> Void,
        completion: @escaping @Sendable (Error?) -> Void
    ) -> any Cancellable {
        let task = Task {
            do {
                guard let url = request.url,
                      let data = try await ImageBridgeKt.loadCoverData(
                          url: url.absoluteString,
                          headers: request.allHTTPHeaderFields ?? [:]
                      )
                else {
                    completion(ImageUnavailable())
                    return
                }
                let response = URLResponse(url: url, mimeType: nil, expectedContentLength: data.count, textEncodingName: nil)
                didReceiveData(data, response)
                completion(nil)
            } catch {
                completion(error)
            }
        }
        return TaskCancellable(task: task)
    }
}

/// The image couldn't be had (404, offline, not an image). Nothing was cached on either side, so a later
/// request tries the network again.
struct ImageUnavailable: Error {}

private struct TaskCancellable: Cancellable {
    let task: Task<Void, Never>

    func cancel() { task.cancel() }
}
