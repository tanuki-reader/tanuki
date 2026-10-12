import Shared
import SwiftUI
import WebKit

/// The browser-session layer (Cloudflare challenges, logins), mounted permanently by `RootView` **behind**
/// the tabs. Everything it decides comes from `SiteSessionViewModel`: which session is up, whether it's still
/// solving out of sight (`revealed`), when the site is covered, when it's done. Swift draws it and reports
/// the browser's snapshots.
///
/// While a challenge solves silently the browser is laid out full size and painting, under the opaque app:
/// occlusion happens in the compositor and the page can't see it, but a shrunk, faded or detached engine
/// never solves. So while silent the layer is hidden only by its z-order (`onTop`), never by opacity or size.
struct SiteSessionLayer: View {
    /// Whether the root must stack the layer over the app: from the reveal until the exit fade has finished.
    @Binding var onTop: Bool

    @State private var model = ScreenModel({ $0.siteSessionViewModel() }, uiState: \.uiState)
    /// The session drawn. Kept after the ViewModel goes idle, so the exit fade has something to draw.
    @State private var shown: SiteSessionStateActive?
    @State private var browser: SiteSessionBrowser?
    /// 0 → 1 across the reveal, 1 → 0 across the exit. Applied only to a revealed session.
    @State private var presence = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if let session = shown, let browser {
                layer(session, browser: browser)
            }
        }
        .onAppear { model.vm.attach() }
        .onDisappear { model.vm.detach() }
        .task { await model.observe() }
        .onChange(of: model.state as? SiteSessionStateActive, initial: true) { _, active in
            update(active)
        }
    }

    private func update(_ active: SiteSessionStateActive?) {
        guard let active else {
            browser?.close()
            dismiss()
            return
        }
        if active.sessionId != shown?.sessionId {
            browser?.close()
            let vm = model.vm
            browser = SiteSessionBrowser(url: active.url, clearCookie: active.clearCookie) { vm.onSnapshot(result: $0) }
        }
        let revealing = active.revealed && shown?.revealed != true
        shown = active
        if revealing {
            onTop = true
            withAnimation(reduceMotion ? TK.Motion.reduced : TK.Motion.enter) { presence = 1 }
        }
    }

    /// Fades a revealed layer out before dropping it behind the app; a silent one just goes.
    private func dismiss() {
        guard shown?.revealed == true else {
            shown = nil
            browser = nil
            return
        }
        withAnimation(reduceMotion ? TK.Motion.reduced : TK.Motion.exit) {
            presence = 0
        } completion: {
            // A new session may have arrived during the fade; it owns the layer now.
            guard model.state is SiteSessionStateIdle else { return }
            onTop = false
            shown = nil
            browser = nil
        }
    }

    private func layer(_ session: SiteSessionStateActive, browser: SiteSessionBrowser) -> some View {
        let revealed = session.revealed
        return VStack(spacing: 0) {
            // Conditional siblings of the browser, not a separate layout: the browser keeps its place in
            // the tree across the reveal, or SwiftUI would rebuild it and lose a half-solved challenge.
            if revealed {
                header(session)
            }
            ZStack {
                BrowserView(webView: browser.webView, interactive: revealed)
                    .id(session.sessionId)
                // Covers the site whenever a challenge isn't on screen, so the user doesn't watch an API
                // host's raw JSON load. Only once revealed: its spinner would animate every frame unseen.
                if revealed && session.covered {
                    connectingCover(host: session.host)
                        .transition(.opacity)
                }
            }
            .animation(TK.Motion.enter, value: session.covered)
            // A login has no cookie that means "signed in", so the user says when it's done.
            if revealed && session.needsConfirm {
                footer
            }
        }
        .background { if revealed { Color.tk.canvas.ignoresSafeArea() } }
        .opacity(revealed ? presence : 1)
        .offset(y: revealed && !reduceMotion ? (1 - presence) * 24 : 0)
        .allowsHitTesting(revealed)
        .accessibilityHidden(!revealed)
    }

    private func header(_ session: SiteSessionStateActive) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s4) {
            HStack(spacing: TK.Space.s3) {
                Button {
                    model.vm.onDismiss()
                } label: {
                    Image(systemName: "xmark")
                }
                .buttonStyle(.glass)
                .accessibilityLabel(tr(session.isChallenge ? "site_session_close" : "site_session_cancel"))
                Text(tr(session.isChallenge ? "site_session_challenge_title" : "site_session_login_title"))
                    .tkStyle(.headline)
                    .foregroundStyle(Color.tk.ink)
                    .lineLimit(1)
            }
            // Why the user is looking at a browser, next to the reason's glyph on an accent-tint plate.
            HStack(spacing: TK.Space.s3) {
                Image(systemName: session.isChallenge ? "checkmark.shield" : "lock")
                    .foregroundStyle(Color.tk.accent)
                    .frame(width: 32, height: 32)
                    .background(Color.tk.accentTint, in: .rect(cornerRadius: TK.Radius.sm))
                Text(tr(session.isChallenge ? "site_session_challenge_message" : "site_session_login_message", session.host))
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.inkMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, TK.Space.s4)
        .padding(.vertical, TK.Space.s3)
        .overlay(alignment: .bottom) { Divider() }
    }

    private func connectingCover(host: String) -> some View {
        VStack(spacing: TK.Space.s4) {
            ProgressView()
            Text(tr("site_session_connecting", host))
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.inkFaint)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, TK.Space.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.tk.canvas)
    }

    private var footer: some View {
        HStack {
            Spacer()
            Button(tr("site_session_done")) { model.vm.onDone() }
                .buttonStyle(.glassProminent)
                .tint(Color.tk.accent)
        }
        .padding(.horizontal, TK.Space.s4)
        .padding(.vertical, TK.Space.s3)
        .overlay(alignment: .top) { Divider() }
    }
}

/// The session's `WKWebView`, owned by the Kotlin `SiteSessionBrowser`. Touches reach it only once the layer
/// is revealed: while silent a tap would land on a browser the user can't see.
private struct BrowserView: UIViewRepresentable {
    let webView: WKWebView
    let interactive: Bool

    func makeUIView(context: Context) -> WKWebView { webView }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        uiView.isUserInteractionEnabled = interactive
    }
}
