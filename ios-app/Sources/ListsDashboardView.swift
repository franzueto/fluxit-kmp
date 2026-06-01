import SwiftUI
import Shared

/// The Lists Dashboard, wired to `ListsDashboardStore` (Phase 06 Slice 7). Proves
/// the iOS composition root end to end: the SKIE-bridged store resolves from Koin,
/// `state` drives the list, and the search field dispatches
/// `SearchQueryChanged` into the real use-case feed. Optimistic delete/undo,
/// navigation, and design-system rows land in the Lists feature phase / Slice 8.
struct ListsDashboardView: View {
    private let store = InitKoinKt.resolveListsDashboardStore()
    @State private var state: ListsState = ListsState(searchQuery: "", lists: LoadStateLoading(), pendingDelete: nil)

    var body: some View {
        List {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, summary in
                VStack(alignment: .leading, spacing: 2) {
                    Text(summary.name).font(.headline)
                    Text("\(summary.completedItems)/\(summary.totalItems) done")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
                .onTapGesture { store.dispatch(intent: ListsIntentOpenList(id: summary.id)) }
            }
        }
        .overlay { statusOverlay }
        .searchable(text: searchBinding)
        .task { await observe(store, into: $state) }
    }

    private var rows: [ListSummary] {
        if case let .loaded(loaded) = onEnum(of: state.lists) {
            return loaded.value as? [ListSummary] ?? []
        }
        return []
    }

    @ViewBuilder private var statusOverlay: some View {
        switch onEnum(of: state.lists) {
        case .loading:
            ProgressView()
        case .empty:
            Text("No lists yet").foregroundStyle(.secondary)
        case let .error(error):
            Text(error.message).foregroundStyle(.secondary)
        case .loaded:
            EmptyView()
        }
    }

    private var searchBinding: Binding<String> {
        Binding(
            get: { state.searchQuery },
            set: { store.dispatch(intent: ListsIntentSearchQueryChanged(query: $0)) }
        )
    }
}
