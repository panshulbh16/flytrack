import FlyTrackCore
import SwiftData
import SwiftUI
import UserNotifications
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

@main
struct FlyTrackApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var shell: AppShell
    /// Kept for the app's lifetime. A ModelContext does not retain its container, and a fetch on a
    /// context whose container was released crashes.
    private let container: ModelContainer

    init() {
        let container = try! ModelContainer(
            for: CachedFlightRecord.self, CachedTrackingRecord.self, CachedProfileRecord.self,
            GmailSuggestionRecord.self, GmailSeenMessageRecord.self
        )
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
        .onChange(of: scenePhase, initial: true) { _, phase in
            guard phase == .active else { return }
            Task { await GmailBackground.appOpened(shell: shell) }
        }
    }
}

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    var shell: AppShell?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Set here, unconditionally: a tap or action that launched the app must reach PushBridge with or
        // without Firebase, and AppShell.bootstrap() does not run on a background launch.
        UNUserNotificationCenter.current().delegate = PushBridge.shared
        GmailNotifications.registerCategory()
        if let shell { GmailBackground.register(shell: shell) }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        guard AppConfig.pushPlistPresent else { return }
        #if canImport(FirebaseMessaging)
        Messaging.messaging().apnsToken = deviceToken
        // Firebase declines the launch-time token request until the APNs token is set and does not
        // retry by itself, so a phone with no cached FCM token would never register. Ask again now.
        Messaging.messaging().token { token, _ in
            guard let token else { return }
            Task { @MainActor in PushBridge.shared.onToken?(token) }
        }
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
    let gmailAPI = GmailAPIClient()
    let gmailStore: SwiftDataGmailStore
    let gmailAuth: GmailAuth
    let shared = SharedContentHolder()
    let clock: FlyClock = SystemClock()

    @Published var session: SessionState = .loading
    @Published var path = NavigationPath()
    /// Bumped when Gmail suggestions change outside the Gmail screen (background check, Not now, wipe), so the
    /// Found in Gmail card reloads.
    @Published var gmailRevision = 0

    init(context: ModelContext) {
        let stack = SupabaseStack(context: context)
        self.stack = stack
        auth = LiveAuthRepository(stack: stack)
        flights = LiveFlightRepository(stack: stack)
        profiles = LiveProfileRepository(stack: stack)
        settings = DeviceSettings()
        push = LivePush(flights: flights, client: stack.client)
        cloud = LiveCloudParser(stack: stack, auth: auth)
        extractor = OnDeviceExtractor()
        gmailStore = SwiftDataGmailStore(context: context)
        gmailAuth = GmailAuth(settings: settings, api: gmailAPI)
        PushBridge.shared.onToken = { token in
            Task { @MainActor in await DeviceRegistrar.register(token: token, client: stack.client) }
        }
        if let client = stack.client {
            // Signing out or into another account changes the user. Register this phone for the new one,
            // as Android does when its session's user id changes. bootstrap() handles the launch.
            let push = push
            Task {
                var lastUser = client.auth.currentSession?.user.id
                for await (_, session) in client.auth.authStateChanges {
                    guard let user = session?.user.id else { lastUser = nil; continue }
                    guard user != lastUser else { continue }
                    lastUser = user
                    if let token = await push.currentToken() {
                        await DeviceRegistrar.register(token: token, client: client)
                    }
                }
            }
        }
        PushBridge.shared.onData = { data in
            Task { @MainActor in await self.handlePush(data) }
        }
        PushBridge.shared.onFlight = { [weak self] id in self?.openFlight(id) }
        PushBridge.shared.onGmailSuggestion = { [weak self] id, dismiss in self?.openGmailSuggestion(id, dismiss: dismiss) }
        auth.onUserDataWiped = { [weak self] in await self?.clearGmail() }
    }

    /// One scanner per run (Gmail screen, app-open check, BGTask). It keeps no state between scans.
    func gmailScanner() -> GmailScanner {
        let clock = clock
        return GmailScanner(
            api: gmailAPI, tokens: gmailAuth, store: gmailStore, settings: settings, tracked: flights, pdf: extractor,
            now: { Int64(clock.now().timeIntervalSince1970 * 1000) },
            today: { clock.today(tz: TimeZone.current.identifier) }
        )
    }

    /// HeyGate sign-out or a user switch ends here (Settings' "Disconnect Gmail" uses GmailImportModel.disconnect()).
    /// Wipes everything Gmail-derived at once, then revokes in an unstructured Task so the cache wipe that follows
    /// this hook does not wait on Google. The revoke runs only while GoogleSignIn still holds the captured account.
    func clearGmail() async {
        let email = await settings.gmailAccountEmail()
        await clearGmailLocal()
        if let email {
            let gmailAuth = gmailAuth
            Task { await gmailAuth.disconnect(storedEmail: email) }
        }
    }

    /// The local part of the wipe, no network: account, suggestions, seen ids, Gmail settings, the BGTask and
    /// Gmail notifications. Also run at launch by a build with the release switch off.
    func clearGmailLocal() async {
        GmailBackground.cancel()
        // Before the store is wiped, so a scan still running fails its account check and writes nothing.
        await settings.setGmailAccountEmail(nil)
        await gmailStore.clear()
        settings.clearGmail()
        // Last, so it also removes what a scan that passed its account check posted meanwhile.
        await GmailNotifications.cancelAll()
        gmailRevision += 1
    }

    func bootstrap() async {
        // A build with Gmail import switched off forgets what a build with it on left (e.g. TestFlight over Debug).
        if GmailNotice.leftoversNeedWipe(importEnabled: AppConfig.isGmailImportEnabled, accountEmail: await settings.gmailAccountEmail()) {
            await clearGmailLocal()
        }
        _ = await auth.ensureSignedIn()
        session = await auth.session()
        push.start()
        if let token = await push.currentToken() {
            await DeviceRegistrar.register(token: token, client: stack.client)
        }
        if session != .notConfigured {
            _ = await flights.refresh()
            _ = await profiles.refresh()
        }
        await FlightLiveActivities.resume(client: stack.client, push: push, flights: flights)
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

    /// Track (or a tap on the body) opens Add flight pre-filled; the suggestion stays pending until Add flight
    /// tracks it. Not now marks it dismissed without opening the app.
    func openGmailSuggestion(_ id: String, dismiss: Bool) {
        GmailNotifications.cancel([id])
        // Not now runs with the app in the background: keep it alive until the dismissal is saved.
        let background = UIApplication.shared.beginBackgroundTask()
        Task {
            defer { UIApplication.shared.endBackgroundTask(background) }
            if dismiss {
                await gmailStore.setStatus([id], .dismissed)
                gmailRevision += 1
                return
            }
            guard let prefill = GmailNotice.trackPrefill(await gmailStore.get(id)) else { return }
            path.append(Route.add(prefill))
        }
    }

    func handlePush(_ data: [String: String]) async {
        await push.handle(data: data)
        await FlightLiveActivities.endFinished(flights: flights)
        session = await auth.session()
    }
}

enum Route: Hashable {
    case add(AddFlightPrefill)
    case detail(String)
    case settings
    case importTicket
    case scanBoardingPass
    case gmailImport
}

struct RootView: View {
    @ObservedObject var shell: AppShell
    @AppStorage(Appearance.storageKey) private var appearance = Appearance.system

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
                    case .scanBoardingPass:
                        ScanScreen(shell: shell)
                    case .gmailImport:
                        GmailImportScreen(shell: shell)
                    }
                }
        }
        .tint(FlyColor.primary)
        .preferredColorScheme(appearance.colorScheme)
        .task { await shell.bootstrap() }
    }
}
