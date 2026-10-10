import Shared
import SwiftUI

/// Categories: the library's shelves, with create and (confirmed) delete. Both dialogs are ViewModel
/// state; only the name being typed is local.
struct CategoriesScreen: View {
    @State private var model = ScreenModel({ $0.categoriesViewModel() }, uiState: \.uiState)
    @State private var newName = ""

    var body: some View {
        let state = model.state
        List {
            if state.categories.isEmpty {
                Text(tr("settings_categories_empty", table: .settings))
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.inkFaint)
                    .listRowBackground(Color.clear)
            }
            ForEach(state.categories, id: \.id) { category in
                Text(category.name)
                    .tkStyle(.body)
                    .foregroundStyle(Color.tk.ink)
                    .listRowBackground(Color.tk.surfaceRaised)
                    .swipeActions {
                        Button(tr("settings_delete", table: .settings), role: .destructive) {
                            model.vm.onDeleteRequested(category: category)
                        }
                    }
            }
        }
        .settingsList()
        .navigationTitle(tr("settings_categories", table: .settings))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Button(tr("settings_add_category", table: .settings), systemImage: "plus") {
                newName = ""
                model.vm.onCreateRequested()
            }
        }
        .alert(tr("settings_new_category_title", table: .settings), isPresented: dialog(state.creating)) {
            TextField(tr("settings_category_name_hint", table: .settings), text: $newName)
            Button(tr("settings_cancel", table: .settings), role: .cancel) { model.vm.onDialogDismissed() }
            Button(tr("settings_create", table: .settings)) { model.vm.onCreateCategory(name: newName) }
        }
        .alert(
            tr("settings_delete_category_title", table: .settings),
            isPresented: dialog(state.pendingDeletion != nil),
            presenting: state.pendingDeletion
        ) { _ in
            Button(tr("settings_cancel", table: .settings), role: .cancel) { model.vm.onDialogDismissed() }
            Button(tr("settings_delete", table: .settings), role: .destructive) { model.vm.onDeleteConfirmed() }
        } message: { category in
            Text(tr("settings_delete_category_message", category.name, table: .settings))
        }
        .task { await model.observe() }
    }

    /// A dialog's presentation, driven by state alone. An alert only closes through its buttons, and
    /// each one tells the ViewModel itself, so SwiftUI's write-back is ignored. Acting on it could
    /// clear the pending category before the confirm button's action reads it.
    private func dialog(_ shown: Bool) -> Binding<Bool> {
        Binding(get: { shown }, set: { _ in })
    }
}
