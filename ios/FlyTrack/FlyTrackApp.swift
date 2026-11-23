import FlyTrackCore
import SwiftData
import SwiftUI
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

@main
struct FlyTrackApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var shell: AppShell
    /// Kept for the app's lifetime. A ModelContext does not retain its container, and a fetch on a
    /// context whose container was released crashes.
    private let container: ModelContainer

    init() {
        let container = try! ModelContainer(for: CachedFlightRecord.self, CachedTrackingRecord.self, CachedProfileRecord.self)
        self.container = container
        let shell = AppShell(context: container.mainContext)
        _shell = State(initialValue: shell)
        delegate.shell = shell
    }

    var body: some Scene {
        WindowGroup {
            RootView(shell: shell)
                .onOpenURL { url in shell.open(url) }
        }
    }
}

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    var shell: AppShell?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        guard AppConfig.pushPlistPresent else { return }
        #if canImport(FirebaseMessaging)
        Messaging.messaging().apnsToken = deviceToken
        #endif
    }

    func application(
        _ application: UIApplication,
        didReceiveRemoteNotification userInfo: [AnyHashable: Any],
        fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void
    ) {
        let data = stringMap(userInfo)
        Task { @MainActor in
            await self.shell?.handlePush(data)
            completionHandler(.newData)
        }
    }
}

private func stringMap(_ userInfo: [AnyHashable: Any]) -> [String: String] {
    var data: [String: String] = [:]
    for (key, value) in userInfo {
        if let key = key as? String, let value = value as? String { data[key] = value }
    }
    return data
}

@MainActor
final class AppShell: ObservableObject {
    let stack: SupabaseStack
    let auth: LiveAuthRepository
    let flights: LiveFlightRepository
    let profiles: LiveProfileRepository
    let settings: DeviceSettings
    let push: LivePush
    let cloud: LiveCloudParser
    let extractor: OnDeviceExtractor
    let shared = SharedContentHolder()
    let clock: FlyClock = SystemClock()

    @Published var session: SessionState = .loading
    @Published var path = NavigationPath()

    init(context: ModelContext) {
        let stack = SupabaseStack(context: context)
        self.stack = stack
        auth = LiveAuthRepository(stack: stack)
        flights = LiveFlightRepository(stack: stack)
        profiles = LiveProfileRepository(stack: stack)
        settings = DeviceSettings()
        push = LivePush(flights: flights)
        cloud = LiveCloudParser(stack: stack, auth: auth)
        extractor = OnDeviceExtractor()
        PushBridge.shared.onToken = { token in
            Task { @MainActor in await DeviceRegistrar.register(token: token, client: stack.client) }
        }
        PushBridge.shared.onData = { data in
            Task { @MainActor in await self.handlePush(data) }
        }
        PushBridge.shared.onFlight = { [weak self] id in self?.openFlight(id) }
    }

    func bootstrap() async {
        _ = await auth.ensureSignedIn()
        session = await auth.session()
        push.start()
        if let token = PushBridge.shared.pendingUnregister {
            await DeviceRegistrar.unregister(token: token, client: stack.client)
            PushBridge.shared.pendingUnregister = nil
        }
        if let token = await push.currentToken() {
            await DeviceRegistrar.register(token: token, client: stack.client)
        }
        if session != .notConfigured {
            _ = await flights.refresh()
            _ = await profiles.refresh()
        }
        if let shared = ShareInbox.consume() {
            self.shared.offer(shared)
            path.append(Route.importTicket)
        }
    }

    func open(_ url: URL) {
        if url.host == "import" || url.path == "/import" {
            if let shared = ShareInbox.consume() { self.shared.offer(shared) }
            path.append(Route.importTicket)
            return
        }
        if let request = resolveLaunch(url: url, flightId: nil, shared: nil), case .openFlight(let id) = request {
            openFlight(id)
        }
    }

    func openFlight(_ id: String) {
        path.append(Route.detail(id))
        Task { _ = await flights.applyServerSync(flightId: id, compactFlightJSON: nil) }
    }

    func handlePush(_ data: [String: String]) async {
        await push.handle(data: data)
        session = await auth.session()
    }
}

enum Route: Hashable {
    case add(AddFlightPrefill)
    case detail(String)
    case settings
    case importTicket
}

struct RootView: View {
    @ObservedObject var shell: AppShell

    var body: some View {
        NavigationStack(path: $shell.path) {
            FlightsScreen(shell: shell)
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .add(let prefill):
                        AddFlightScreen(shell: shell, prefill: prefill)
                    case .detail(let id):
                        DetailScreen(shell: shell, flightId: id)
                    case .settings:
                        SettingsScreen(shell: shell)
                    case .importTicket:
                        ImportScreen(shell: shell)
                    }
                }
        }
        .tint(FlyColor.ink)
        .task { await shell.bootstrap() }
    }
}
