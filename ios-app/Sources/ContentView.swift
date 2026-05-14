import SwiftUI
import Shared

struct ContentView: View {
    private let platformName = Platform().name

    var body: some View {
        VStack(spacing: 8) {
            Text("FluxIt")
                .font(.largeTitle)
            Text(platformName)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
