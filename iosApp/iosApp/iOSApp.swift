import SwiftUI

@main
struct iOSApp: App {
    // FB-007: gives FirebaseApp.configure() a deterministic home that runs before
    // any SwiftUI scene or view body - and therefore before ContentView creates the
    // Compose view controller that starts Koin. See FirebaseBootstrap.swift.
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
