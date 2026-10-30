import SwiftUI

/// Placeholder shell after the v2 Now/Projects UI was removed. Replaced by the chat-first screens.
struct ContentView: View {
    @Bindable var model: AppModel

    var body: some View {
        VStack(spacing: 12) {
            Text("Luvia")
                .font(.largeTitle.weight(.semibold))
            Text(model.hosts.isEmpty ? "No computers paired" : "\(model.hosts.count) paired")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear { model.connectHostsOnce() }
    }
}
