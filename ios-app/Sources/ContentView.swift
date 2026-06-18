import Shared
import SwiftUI

/// The iOS composition-root view (Phase 06 Slice 7; tab host + nav graph fleshed
/// out in Phase 07 Slice 7). Resolves the session-scoped `RootStore`, runs
/// `InitializeApp` once via `AppStarted`, and gates on the resulting `InitState`:
///  - `.initializing` → a splash spinner.
///  - `.failed` → a minimal retry surface (polished splash UX is a later phase).
///  - `.ready` → the `TabHostView`.
///
/// App-level deep links (reminder taps; plan/06 §5) arrive via `.onOpenURL` →
/// `RootIntent.OpenDeepLink`, and the resulting `NavigateToList`/`NavigateToItem`
/// effects are translated into navigation pushes inside `TabHostView`.
struct ContentView: View {
    private let store = InitKoinKt.resolveRootStore()
    @State private var state = RootState(init: InitStateInitializing(), currentTab: .lists)

    var body: some View {
        Group {
            switch onEnum(of: state.`init`) {
            case .initializing:
                ProgressView()
            case let .failed(failure):
                VStack(spacing: 12) {
                    Text(failure.message)
                    Button("Retry") { store.dispatch(intent: RootIntentAppStarted()) }
                }
            case .ready:
                TabHostView(store: store, currentTab: state.currentTab)
            }
        }
        .task {
            store.dispatch(intent: RootIntentAppStarted())
            await observe(store, into: $state)
        }
        .onOpenURL { url in
            store.dispatch(intent: RootIntentOpenDeepLink(url: url.absoluteString))
        }
    }
}

/// Pushed destinations for a tab's `NavigationStack`. Item detail renders the real
/// Edit-Item screen (Phase 10); Settings is a real stub (Slice 6). Create-List is
/// **not** a stack route — it's a `.fullScreenCover`
/// modal owned by `createListPresented` (plan/09 §1). Deep links push
/// `.listDetail` / `.itemDetail`.
private enum DashRoute: Hashable {
    case listDetail(String)
    case itemDetail(String)
    case settings
}

/// The bottom-tab host (plan/07 §2). The four tabs render unconditionally; the
/// selected tab is owned by `RootStore.currentTab` and a tap dispatches
/// `TabSelected`. Lists + Account each own a `NavigationStack`; Calendar/Starred
/// render the inline "Coming soon" placeholder (ADR-004). The center FAB overlays
/// the bottom bar on the Lists tab. `RootStore` deep-link effects switch to the
/// Lists tab and push the target route.
private struct TabHostView: View {
    let store: RootStore
    let currentTab: Shared.Tab

    @State private var listsPath: [DashRoute] = []
    @State private var accountPath: [DashRoute] = []
    @State private var createListPresented = false

    private static let tabsOrder: [Shared.Tab] = [.lists, .calendar, .starred, .account]

    /// The bottom tab bar is part of this scaffold, so it would otherwise stay
    /// docked over every pushed destination — including `ListDetailView`, whose own
    /// scaffold owns the sticky `ComposerDock`. Two scaffolds claiming the bottom
    /// safe-area inset collide and the tab bar wins, hiding the composer. Hide the
    /// tab bar whenever the active tab has pushed a destination (standard iOS detail
    /// behavior) so the detail screen owns the full bottom area.
    private var chromeHidden: Bool {
        switch currentTab {
        case .lists: return !listsPath.isEmpty
        case .account: return !accountPath.isEmpty
        default: return false
        }
    }

    private var tabItems: [FluxItTabItem] {
        [
            FluxItTabItem(icon: FluxItTokens.Icons.list, activeIcon: FluxItTokens.Icons.listFilled, label: "Lists"),
            FluxItTabItem(icon: FluxItTokens.Icons.calendar, activeIcon: FluxItTokens.Icons.calendarFilled, label: "Calendar"),
            FluxItTabItem(icon: FluxItTokens.Icons.star, activeIcon: FluxItTokens.Icons.starFilled, label: "Starred"),
            FluxItTabItem(icon: FluxItTokens.Icons.account, activeIcon: FluxItTokens.Icons.accountFilled, label: "Account"),
        ]
    }

    var body: some View {
        FluxItScaffold(
            bottomBar: {
                if !chromeHidden {
                    FluxItBottomTabBar(
                        tabs: tabItems,
                        selectedIndex: TabHostView.tabsOrder.firstIndex(of: currentTab) ?? 0,
                        onSelect: { index in store.dispatch(intent: RootIntentTabSelected(tab: TabHostView.tabsOrder[index])) }
                    )
                }
            }
        ) {
            ZStack(alignment: .bottom) {
                tabContent
                // The create-list FAB belongs to the dashboard root only. On a pushed
                // detail screen it would overlay the list-detail ComposerDock and read
                // as "add item" while actually creating a list (the +-button is hidden
                // once `listsPath` is non-empty).
                if currentTab == .lists, listsPath.isEmpty {
                    FluxItFab(icon: FluxItTokens.Icons.plus, accessibilityLabel: "Create new list") {
                        createListPresented = true
                    }
                    .padding(.bottom, FluxItTokens.Spacing.scaleXl)
                }
            }
        }
        .fullScreenCover(isPresented: $createListPresented) {
            CreateListView(
                editingId: nil,
                onDismiss: { createListPresented = false },
                onCreated: { id in
                    createListPresented = false
                    listsPath.append(.listDetail(id))
                }
            )
        }
        .task {
            await observeEffects(store) { effect in
                switch onEnum(of: effect) {
                case let .navigateToList(e):
                    store.dispatch(intent: RootIntentTabSelected(tab: .lists))
                    listsPath.append(.listDetail(e.listId()))
                case let .navigateToItem(e):
                    store.dispatch(intent: RootIntentTabSelected(tab: .lists))
                    listsPath.append(.itemDetail(e.itemId()))
                case .navigateToOnboarding, .showFatalError:
                    break
                }
            }
        }
    }

    @ViewBuilder private var tabContent: some View {
        switch currentTab {
        case .lists:
            NavigationStack(path: $listsPath) {
                ListsDashboardView(
                    onOpenList: { listsPath.append(.listDetail($0)) },
                    onCreateList: { createListPresented = true },
                    onOpenSettings: { listsPath.append(.settings) }
                )
                .navigationDestination(for: DashRoute.self) { route in destination(route, path: $listsPath) }
            }
        case .calendar:
            ComingSoonView(feature: "Calendar")
        case .starred:
            ComingSoonView(feature: "Starred")
        case .account:
            NavigationStack(path: $accountPath) {
                AccountView(onOpenSettings: { accountPath.append(.settings) })
                    .navigationDestination(for: DashRoute.self) { route in destination(route, path: $accountPath) }
            }
        }
    }

    @ViewBuilder private func destination(_ route: DashRoute, path: Binding<[DashRoute]>) -> some View {
        switch route {
        case let .listDetail(id):
            ListDetailView(
                listId: id,
                onBack: { path.wrappedValue.removeLast() },
                onOpenEditItem: { itemId in path.wrappedValue.append(.itemDetail(itemId)) }
            )
        case let .itemDetail(id):
            ItemDetailView(itemId: id)
        case .settings:
            SettingsView(onBack: { path.wrappedValue.removeLast() })
        }
    }
}

private struct ComingSoonView: View {
    let feature: String

    var body: some View {
        FluxItEmptyState(title: "\(feature) is coming soon", message: "Coming in a future update.")
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
            .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
    }
}
