import Nuke
import NukeUI
import SwiftUI

/// A remote image (a cover, a source icon), loaded through the app's pipeline: the Cloudflare-aware
/// Kotlin fetch and disk cache, plus Nuke's decoding and memory cache (`ImagePipeline.tanuki`).
///
/// Screens use this and nothing else. They never import Nuke and never call the Kotlin bridge.
/// `placeholder` shows while loading, on failure and when there's no URL. A loaded image fades in over
/// it and fills the frame the caller gives it.
///
/// ```swift
/// TanukiImage(url: item.coverUrl, headers: item.coverHeaders) { CoverGradient(seed: item.mangaId) }
///     .frame(width: 96)
/// ```
struct TanukiImage<Placeholder: View>: View {
    let url: String?
    var headers: [String: String] = [:]
    @ViewBuilder let placeholder: () -> Placeholder

    var body: some View {
        // ponytail: no resize processor; a full decode of a ~512px cover is cheap. Add
        // ImageProcessors.Resize at the view's size when the reader brings full-size pages.
        LazyImage(request: request, transaction: Transaction(animation: TK.Motion.ease())) { state in
            if let image = state.image {
                image.resizable().scaledToFill()
            } else {
                placeholder()
            }
        }
        .pipeline(.tanuki)
    }

    private var request: ImageRequest? {
        guard let url, let parsed = URL(string: url) else { return nil }
        var urlRequest = URLRequest(url: parsed)
        for (name, value) in headers {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        return ImageRequest(urlRequest: urlRequest)
    }
}
