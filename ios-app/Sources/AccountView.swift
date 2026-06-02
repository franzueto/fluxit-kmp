import Shared
import SwiftUI

/// The Account tab (plan/07 §1/§7), mirroring the Android `AccountScreen`. Wired
/// to `AccountStore`: it shows the app version and routes the Settings row into a
/// pushed `SettingsView`. In debug builds it also exposes the "Seed sample data"
/// action; the button is compiled out of release via `#if DEBUG` (the SwiftUI
/// equivalent of Android's `src/debug` / `src/release` source-set strip).
struct AccountView: View {
    let onOpenSettings: () -> Void

    private let store = InitKoinKt.resolveAccountStore()
    @State private var state = AccountState(version: "", flags: [:])

    var body: some View {
        VStack(spacing: 0) {
            FluxItTopBarLarge(title: "Account")
            ScrollView {
                VStack(spacing: FluxItTokens.Spacing.stackGap) {
                    FluxItDashboardListItem(
                        icon: FluxItTokens.Icons.settings,
                        iconTint: FluxItTokens.Colors.textMuted,
                        title: "Settings",
                        subtitle: "Version \(state.version)",
                        onTap: { store.dispatch(intent: AccountIntentOpenSettings()) },
                        chevronIcon: FluxItTokens.Icons.chevronRight
                    )
                    #if DEBUG
                    DebugSeedSection()
                    #endif
                }
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.top, FluxItTokens.Spacing.scaleMd)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await observe(store, into: $state) }
        .task {
            await observeEffects(store) { effect in
                switch onEnum(of: effect) {
                case .navigateToSettings, .navigateToAbout:
                    onOpenSettings()
                }
            }
        }
    }
}

/// The Settings stub (plan/07 §12), mirroring the Android `SettingsScreen`.
/// Deliberately minimal — full Diagnostics + crash-reporting wiring is Phase 16.
/// About (version), Privacy (a no-op crash-reports toggle + Privacy Policy / ToS
/// rows opening the system browser), and the debug seed section.
struct SettingsView: View {
    let onBack: () -> Void

    @Environment(\.openURL) private var openURL
    private let store = InitKoinKt.resolveAccountStore()
    @State private var state = AccountState(version: "", flags: [:])
    @State private var crashReports = true

    private static let privacyPolicyURL = URL(string: "https://fluxit.example/privacy")!
    private static let termsURL = URL(string: "https://fluxit.example/terms")!

    var body: some View {
        VStack(spacing: 0) {
            FluxItTopBarCentered(title: "Settings", backLabel: "Account", onBack: onBack)
            ScrollView {
                VStack(alignment: .leading, spacing: FluxItTokens.Spacing.stackGap) {
                    FluxItSectionHeader(label: "About")
                    FluxItDashboardListItem(
                        icon: FluxItTokens.Icons.more,
                        iconTint: FluxItTokens.Colors.textMuted,
                        title: "Version",
                        subtitle: state.version
                    )

                    FluxItSectionHeader(label: "Privacy")
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Anonymous crash reports")
                                .font(FluxItTokens.Typography.bodyMd.font)
                                .foregroundStyle(FluxItTokens.Colors.textPrimary)
                            Text("Takes effect on next launch")
                                .font(FluxItTokens.Typography.captionXs.font)
                                .foregroundStyle(FluxItTokens.Colors.textMuted)
                        }
                        Spacer()
                        Toggle("", isOn: $crashReports).labelsHidden()
                    }
                    .padding(.horizontal, FluxItTokens.Spacing.itemPaddingX)
                    FluxItDashboardListItem(
                        icon: FluxItTokens.Icons.more,
                        iconTint: FluxItTokens.Colors.textMuted,
                        title: "Privacy Policy",
                        onTap: { openURL(SettingsView.privacyPolicyURL) },
                        chevronIcon: FluxItTokens.Icons.chevronRight
                    )
                    FluxItDashboardListItem(
                        icon: FluxItTokens.Icons.more,
                        iconTint: FluxItTokens.Colors.textMuted,
                        title: "Terms of Service",
                        onTap: { openURL(SettingsView.termsURL) },
                        chevronIcon: FluxItTokens.Icons.chevronRight
                    )

                    #if DEBUG
                    DebugSeedSection()
                    #endif
                }
                .padding(.horizontal, FluxItTokens.Spacing.containerPadding)
                .padding(.top, FluxItTokens.Spacing.scaleMd)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(FluxItTokens.Colors.backgroundDark.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await observe(store, into: $state) }
    }
}

#if DEBUG
/// Debug-only "Seed sample data" action (plan/07 §7), resolving the shared
/// `SeedSampleData` use case. Compiled out of release builds via `#if DEBUG` —
/// the iOS counterpart of Android's `src/debug` / `src/release` strip.
private struct DebugSeedSection: View {
    private let seed = InitKoinKt.resolveSeedSampleData()
    @State private var seeding = false
    @State private var status: String?

    var body: some View {
        VStack(alignment: .leading, spacing: FluxItTokens.Spacing.scaleSm) {
            FluxItSectionHeader(label: "Debug")
            FluxItPrimaryButton(label: seeding ? "Seeding…" : "Seed sample data", enabled: !seeding) {
                Task { await runSeed() }
            }
            if let status {
                Text(status)
                    .font(FluxItTokens.Typography.captionXs.font)
                    .foregroundStyle(FluxItTokens.Colors.textMuted)
            }
        }
    }

    @MainActor private func runSeed() async {
        seeding = true
        defer { seeding = false }
        do {
            let result = try await seed.invoke()
            switch onEnum(of: result) {
            case let .ok(ok):
                status = "Seeded \((ok.value as? KotlinInt)?.intValue ?? 0) sample lists."
            case .err:
                status = "Seed failed."
            }
        } catch {
            status = "Seed failed: \(error.localizedDescription)"
        }
    }
}
#endif
