import Shared
import SwiftUI

/// Sources (design system: `SourcesScreen`, `SourceRow`, `AddSourceRepo`): installed sources, the ones the
/// user's repos offer, and the repos. Two ViewModels share it: the source lists, and the repos with their
/// add flow. With no repo and nothing installed it is the first-run empty state, whose one action is
/// Add repo. Every dialog is ViewModel state; only the URL being typed is local.
struct SourcesScreen: View {
    @State private var model = ScreenModel({ $0.sourcesViewModel() }, uiState: \.uiState)
    @State private var repos = ScreenModel({ $0.sourceReposViewModel() }, uiState: \.uiState)
    @State private var url = ""

    var body: some View {
        let state = model.state
        let repoState = repos.state
        content(state, repoState)
            .navigationTitle(tr("settings_sources", table: .settings))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button(tr("settings_source_repo_add", table: .settings), systemImage: "plus") { addRepo() }
            }
            .sheet(isPresented: Binding(
                get: { repoState.adding || repoState.preview != nil },
                set: { if !$0 { repos.vm.onDialogDismissed() } }
            )) {
                AddRepoSheet(state: repoState, url: $url, vm: repos.vm)
            }
            .alert(
                tr("settings_remove_source_title", state.pendingRemoval?.name ?? "", table: .settings),
                // State-driven only: every button tells the ViewModel itself (see CategoriesScreen.dialog).
                isPresented: dialog(state.pendingRemoval != nil)
            ) {
                Button(tr("settings_remove", table: .settings), role: .destructive) { model.vm.onRemovalConfirmed() }
                if let usage = state.pendingUsage, !usage.isEmpty {
                    Button(tr("settings_remove_source_downloads", Int(usage.chapters), table: .settings),
                           role: .destructive) {
                        model.vm.onRemovalDeleteDownloadsChange(deleteDownloads: true)
                        model.vm.onRemovalConfirmed()
                    }
                }
                Button(tr("settings_cancel", table: .settings), role: .cancel) { model.vm.onRemovalDismissed() }
            } message: {
                Text(removalMessage(state))
            }
            .alert(
                tr("settings_source_repo_remove_title", repoState.pendingRemoval?.name ?? "", table: .settings),
                isPresented: dialog(repoState.pendingRemoval != nil)
            ) {
                Button(tr("settings_cancel", table: .settings), role: .cancel) { repos.vm.onDialogDismissed() }
                Button(tr("settings_remove", table: .settings), role: .destructive) { repos.vm.onRemoveConfirmed() }
            } message: {
                Text(tr("settings_source_repo_remove_message", table: .settings))
            }
            .alert(
                tr(repoState.error != nil ? "settings_source_repo_error_title" : "settings_source_repo_refresh_error_title",
                   table: .settings),
                isPresented: dialog(!repoState.adding && repoState.preview == nil
                    && (repoState.error != nil || repoState.refreshError != nil))
            ) {
                Button(tr("settings_ok", table: .settings)) { repos.vm.onDialogDismissed() }
            } message: {
                if let error = repoState.error ?? repoState.refreshError { Text(repoMessage(error)) }
            }
            .task { await model.observe() }
            .task { await repos.observe() }
    }

    private func addRepo() {
        url = ""
        repos.vm.onAddRequested()
    }

    @ViewBuilder
    private func content(_ state: SourcesState, _ repoState: SourceReposState) -> some View {
        if state.loading || repoState.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(Color.tk.canvas)
        } else if state.repoCount == 0 && state.installed.isEmpty {
            ContentUnavailableView {
                VStack(spacing: TK.Space.s3) {
                    Image("LaunchLogo").renderingMode(.template).resizable().scaledToFit().frame(width: 56, height: 56)
                        .foregroundStyle(Color.tk.inkFaint)
                    Text(tr("settings_sources_empty_title", table: .settings))
                        .tkStyle(.titleL)
                        .foregroundStyle(Color.tk.ink)
                }
            } description: {
                Text(tr("settings_sources_no_repos", table: .settings))
            } actions: {
                Button(tr("settings_source_repo_add", table: .settings), systemImage: "plus") { addRepo() }
                    .buttonStyle(.glassProminent)
                    .tint(Color.tk.accent)
            }
            .background(Color.tk.canvas)
        } else {
            List {
                if !state.languages.isEmpty {
                    languageChips(state)
                }
                if state.installed.isEmpty && state.available.isEmpty {
                    Text(tr("settings_sources_empty", table: .settings))
                        .tkStyle(.body)
                        .foregroundStyle(Color.tk.inkFaint)
                        .listRowBackground(Color.clear)
                }
                if !state.installed.isEmpty {
                    Section(tr("settings_sources_installed", table: .settings)) {
                        ForEach(state.installed, id: \.sourceId) { source in
                            row(source, state: state, installed: true)
                                .swipeActions {
                                    Button(tr("settings_remove", table: .settings), role: .destructive) {
                                        model.vm.onRemovalRequested(sourceId: source.sourceId)
                                    }
                                }
                                .accessibilityAction(named: tr("settings_remove_source_action", source.name, table: .settings)) {
                                    model.vm.onRemovalRequested(sourceId: source.sourceId)
                                }
                        }
                    }
                }
                if !state.available.isEmpty {
                    Section {
                        ForEach(state.available, id: \.sourceId) { source in
                            row(source, state: state, installed: false)
                        }
                    } header: {
                        Text(tr("settings_sources_available", table: .settings))
                    } footer: {
                        Text(tr("settings_sources_available_hint", table: .settings))
                    }
                }
                if !repoState.repos.isEmpty {
                    Section(tr("settings_source_repos", table: .settings)) {
                        ForEach(repoState.repos, id: \.baseUrl) { repo in
                            repoRow(repo)
                                .swipeActions {
                                    Button(tr("settings_remove", table: .settings), role: .destructive) {
                                        repos.vm.onRemoveRequested(repo: repo)
                                    }
                                }
                                .accessibilityAction(named: tr("settings_source_repo_remove_action", repo.name, table: .settings)) {
                                    repos.vm.onRemoveRequested(repo: repo)
                                }
                        }
                    }
                }
            }
            .settingsList()
            .refreshable { repos.vm.onRefresh() }
        }
    }

    /// `All` plus one chip per language, with its count. Names are the system's, in the user's language.
    private func languageChips(_ state: SourcesState) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: TK.Space.s2) {
                FilterChip(title: tr("settings_languages_all", table: .settings), selected: state.selectedLanguage == nil) {
                    model.vm.onLanguageSelected(tag: nil)
                }
                ForEach(state.languages, id: \.tag) { language in
                    FilterChip(title: languageName(language.tag), count: Int(language.count),
                               selected: state.selectedLanguage == language.tag) {
                        model.vm.onLanguageSelected(tag: language.tag)
                    }
                }
            }
        }
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }

    /// One source and its life cycle (`SourceRow`): words for every state, never color alone.
    private func row(_ source: SourceItem, state: SourcesState, installed: Bool) -> some View {
        let blocked = !installed && source.compatibility != .compatible
        return HStack(spacing: TK.Space.s3) {
            SourceIcon(name: source.name, iconUrl: source.iconUrl)
                .opacity(blocked ? 0.4 : 1)
            VStack(alignment: .leading, spacing: TK.Space.s0_5) {
                HStack(spacing: TK.Space.s2) {
                    Text(source.name).tkStyle(.body).foregroundStyle(Color.tk.ink).lineLimit(1)
                    if source.nsfw {
                        Text(tr("settings_source_adult", table: .settings))
                            .tkStyle(.caption)
                            .foregroundStyle(Color.tk.inkMuted)
                            .padding(.horizontal, TK.Space.s1)
                            .background(Color.tk.surfaceHover, in: Capsule())
                    }
                }
                status(source, state: state, installed: installed, blocked: blocked)
            }
            Spacer()
            trailing(source, installed: installed, blocked: blocked)
        }
        .listRowBackground(Color.tk.surfaceRaised)
    }

    @ViewBuilder
    private func status(_ source: SourceItem, state: SourcesState, installed: Bool, blocked: Bool) -> some View {
        if let progress = source.progress {
            Text(progressText(progress)).tkStyle(.numeric).foregroundStyle(Color.tk.inkMuted)
        } else if installed && source.removeFailed {
            failureLine(tr("settings_source_remove_failed", table: .settings))
        } else if let failure = source.installFailure {
            failureLine(installMessage(failure))
        } else if blocked {
            Label(tr(source.compatibility == .outdated ? "settings_source_outdated" : "settings_source_needs_update",
                     table: .settings), systemImage: "exclamationmark.triangle")
                .tkStyle(.caption)
                .foregroundStyle(Color.tk.warning)
        } else if let details = details(source, state: state, installed: installed) {
            Text(details).tkStyle(.caption).foregroundStyle(Color.tk.inkFaint)
        }
    }

    @ViewBuilder
    private func trailing(_ source: SourceItem, installed: Bool, blocked: Bool) -> some View {
        if let progress = source.progress {
            if case let .downloading(step) = onEnum(of: progress), let fraction = step.fraction?.floatValue {
                ProgressView(value: Double(fraction)).progressViewStyle(.circular).tint(Color.tk.accent)
            } else {
                ProgressView()
            }
        } else if source.removing {
            ProgressView()
        } else if installed || blocked {
            EmptyView()
        } else if source.installFailure != nil {
            Button(tr("settings_source_retry", table: .settings)) { model.vm.onInstall(sourceId: source.sourceId) }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
        } else {
            Button(tr("settings_install", table: .settings)) { model.vm.onInstall(sourceId: source.sourceId) }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
        }
    }

    private func repoRow(_ repo: SourceRepoItem) -> some View {
        VStack(alignment: .leading, spacing: TK.Space.s0_5) {
            Text(repo.name).tkStyle(.body).foregroundStyle(Color.tk.ink)
            Text(repo.host).tkStyle(.caption).foregroundStyle(Color.tk.inkFaint)
            // Fails closed, never silently: the last verified index keeps serving, and the row says why.
            if let problem = repo.problem, problem == .keyChanged || problem == .badSignature {
                failureLine(tr(problem == .keyChanged ? "settings_source_repo_key_changed" : "settings_source_repo_tampered",
                               table: .settings))
            }
        }
        .listRowBackground(Color.tk.surfaceRaised)
    }

    private func failureLine(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.circle")
            .tkStyle(.caption)
            .foregroundStyle(Color.tk.danger)
    }

    private func progressText(_ progress: InstallProgress) -> String {
        switch onEnum(of: progress) {
        case let .downloading(step):
            if let fraction = step.fraction?.floatValue {
                return tr("settings_source_downloading_percent", Int((fraction * 100).rounded()), table: .settings)
            }
            return tr("settings_source_downloading", table: .settings)
        case .verifying:
            return tr("settings_source_verifying", table: .settings)
        }
    }

    /// Language · version, plus the repo when more than one is added.
    private func details(_ source: SourceItem, state: SourcesState, installed: Bool) -> String? {
        guard let tag = source.lang else { return nil }
        let lang = languageName(tag)
        guard let version = source.version?.intValue else { return lang }
        if state.showRepo, !installed, let repo = source.repoName {
            return tr("settings_source_details_available", lang, version, repo, table: .settings)
        }
        return tr("settings_source_details_installed", lang, version, table: .settings)
    }

    private func installMessage(_ error: SourceRepoError) -> String {
        switch error {
        case .offline: tr("settings_source_failed_offline", table: .settings)
        case .mismatch: tr("settings_source_failed_mismatch", table: .settings)
        case .unreachable: tr("settings_source_failed_unreachable", table: .settings)
        default: tr("settings_source_install_failed", table: .settings)
        }
    }

    /// The message, plus what deleting the downloads would free once that is known.
    private func removalMessage(_ state: SourcesState) -> String {
        let message = tr("settings_remove_source_message", table: .settings)
        guard let usage = state.pendingUsage, !usage.isEmpty else { return message }
        return message + "\n\n" + tr("settings_remove_source_downloads_hint", fileSize(usage.bytes), table: .settings)
    }

    /// State-driven presentation; see CategoriesScreen.dialog.
    private func dialog(_ shown: Bool) -> Binding<Bool> {
        Binding(get: { shown }, set: { _ in })
    }
}

/// Adding a repo in one glass sheet (`AddSourceRepo`): the address, then the repo it describes — name,
/// source count, host and the key fingerprint — before anything is saved. Add repo is the one prominent
/// action. The field stays solid: it is content.
private struct AddRepoSheet: View {
    let state: SourceReposState
    @Binding var url: String
    let vm: SourceReposViewModel

    var body: some View {
        NavigationStack {
            Form {
                if let repo = state.preview {
                    Section {
                        VStack(alignment: .leading, spacing: TK.Space.s1) {
                            Text(repo.name).tkStyle(.headline).foregroundStyle(Color.tk.ink)
                            Text(tr("settings_source_repo_summary", Int(repo.sourceCount?.intValue ?? 0), repo.host,
                                    table: .settings))
                                .tkStyle(.caption)
                                .foregroundStyle(Color.tk.inkMuted)
                        }
                    }
                    Section {
                        Text(repo.fingerprint).tkStyle(.numeric).monospaced().foregroundStyle(Color.tk.ink)
                    } header: {
                        Label(tr("settings_source_repo_signed", table: .settings), systemImage: "checkmark.shield")
                            .symbolRenderingMode(.hierarchical)
                    } footer: {
                        Text(tr("settings_source_repo_trust", table: .settings))
                    }
                } else {
                    Section {
                        TextField(tr("settings_source_repo_url_placeholder", table: .settings), text: $url)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.go)
                            .onSubmit(submit)
                            .listRowBackground(Color.tk.surfaceRaised)
                    } header: {
                        Text(tr("settings_source_repo_url_label", table: .settings))
                    } footer: {
                        if let error = state.error {
                            Label(repoMessage(error), systemImage: "exclamationmark.circle").foregroundStyle(Color.tk.danger)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .navigationTitle(tr("settings_source_repo_add", table: .settings))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(tr("settings_cancel", table: .settings), systemImage: "xmark") { vm.onDialogDismissed() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Group {
                    if state.busy {
                        ProgressView().frame(maxWidth: .infinity)
                    } else if state.preview != nil {
                        Button { vm.onPreviewConfirmed() } label: {
                            Text(tr("settings_source_repo_add", table: .settings)).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glassProminent)
                        .tint(Color.tk.accent)
                    } else {
                        Button(action: submit) {
                            Text(tr("settings_source_repo_continue", table: .settings)).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.glass)
                        .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                .controlSize(.large)
                .padding(TK.Space.s4)
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit() {
        guard !url.trimmingCharacters(in: .whitespaces).isEmpty, !state.busy else { return }
        vm.onUrlSubmitted(url: url)
    }
}

private func repoMessage(_ error: SourceRepoError) -> String {
    switch error {
    case .invalidUrl: tr("settings_source_repo_error_invalid_url", table: .settings)
    case .unreachable: tr("settings_source_repo_error_unreachable", table: .settings)
    case .unsigned: tr("settings_source_repo_error_unsigned", table: .settings)
    case .badSignature: tr("settings_source_repo_error_bad_signature", table: .settings)
    case .keyChanged: tr("settings_source_repo_error_key_changed", table: .settings)
    case .mismatch: tr("settings_source_repo_error_mismatch", table: .settings)
    case .unsupportedFormat: tr("settings_source_repo_error_unsupported", table: .settings)
    case .offline: tr("settings_source_repo_error_offline", table: .settings)
    case .alreadyAdded: tr("settings_source_repo_error_already_added", table: .settings)
    }
}

/// A BCP-47 tag as a language name in the user's language: `pt-BR` → "Português (Brasil)".
private func languageName(_ tag: String) -> String {
    Locale.current.localizedString(forIdentifier: tag)?.capitalized(with: .current) ?? tag
}
