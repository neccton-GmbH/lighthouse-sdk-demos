import SwiftUI

@main
struct LighthouseDemoApp: App {
    @StateObject private var model = DemoAppModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                .preferredColorScheme(.dark)
        }
    }
}
