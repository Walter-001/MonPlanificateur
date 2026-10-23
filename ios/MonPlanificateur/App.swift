import SwiftUI
import UIKit

@main
struct MonPlanificateurApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup { PlannerView() }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        return true
    }
}

struct PlannerView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> PlannerViewController { PlannerViewController() }
    func updateUIViewController(_ uiViewController: PlannerViewController, context: Context) {}
}
