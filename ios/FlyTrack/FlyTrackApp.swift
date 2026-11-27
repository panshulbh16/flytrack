import Combine
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
            // Delivery receipts for alerts that arrived while the app was in the background (P2 contract section 3).
            Task { await shell.receipts.sweepDelivered() }
            // A delay heard while the app was away moves the "time to leave" reminder (P2 wave 2 section 5).
            Task { await shell.resyncLeaveReminders() }
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
    let billing: BillingGateway
    let pro: ProCenter
    let sharing: LiveFlightSharing
    let checkinRules: LiveCheckinRules
    let airportHealth: LiveAirportHealth
    let passport: LivePassport
    let alertPrefs: LiveAlertPrefs
    let receipts: PushReceipts
    // P2 wave 2 (docs/P2_WAVE2_CONTRACTS.md).
    let friends: LiveFriends
    let forwarding: LiveForwarding
    let calendarFeed: LiveCalendarFeed
    let forecast: LiveForecast
    let travelTimes: DeviceTravelTimes
    let leaveReminders: LocalLeaveReminders
    // P3 flight intelligence and widgets (docs/P3_CONTRACTS.md A2, A4, A5, B1–B4, B7).
    let intel: LiveFlightIntel
    let widgets = WidgetPublisher()
    // P3 search / social / watch (docs/P3_CONTRACTS.md A3, B5, B6, B9, B10, B12, B13).
    let p3: P3Center
    // P4: radar, TripIt, Gift Pro, terminal maps (docs/P4_CONTRACTS.md).
    let p4: P4Center
    private var p3Changes: AnyCancellable?
    private var p4Changes: AnyCancellable?
    private var crewModeChanges: AnyCancellable?

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
        let billing = makeBillingGateway()
        self.billing = billing
        // P3 B13: a family member has no Pro of their own in `profiles`; the cached family grant fills the gap.
        let familyGrant = FamilyGrantCache(stack: stack)
        pro = ProCenter(service: ProService(
            billing: billing, profiles: profiles, auth: auth, sync: LiveEntitlementSync(stack: stack), clock: clock,
            family: familyGrant
        ))
        sharing = LiveFlightSharing(stack: stack, auth: auth)
        checkinRules = LiveCheckinRules(stack: stack)
        airportHealth = LiveAirportHealth(stack: stack)
        passport = LivePassport(stack: stack)
        alertPrefs = LiveAlertPrefs(stack: stack)
        let wave2 = Wave2Backend(stack: stack, auth: auth)
        friends = LiveFriends(backend: wave2)
        forwarding = LiveForwarding(backend: wave2)
        calendarFeed = LiveCalendarFeed(backend: wave2)
        forecast = LiveForecast(backend: wave2)
        intel = LiveFlightIntel(backend: wave2)
        p3 = P3Center(stack: stack, flights: flights, backend: wave2, familyGrant: familyGrant)
        p4 = P4Center(stack: stack, auth: auth, backend: wave2, billing: billing)
        travelTimes = DeviceTravelTimes()
        leaveReminders = LocalLeaveReminders()
        let receipts = PushReceipts(sender: LivePushAck(stack: stack))
        self.receipts = receipts
        push.receipts = receipts
        PushBridge.shared.onDelivered = { items in receipts.received(items) }
        PushBridge.shared.onToken = { token in
            Task { @MainActor in await DeviceRegistrar.register(token: token, client: stack.client) }
        }
        if let client = stack.client {
            // Signing out or into another account changes the user. Register this phone for the new one,
            // as Android does when its session's user id changes. bootstrap() handles the launch.
            let push = push
            let pro = pro
            let p3 = p3
            Task {
                var lastUser = client.auth.currentSession?.user.id
                for await (_, session) in client.auth.authStateChanges {
                    guard let user = session?.user.id else { lastUser = nil; continue }
                    guard user != lastUser else { continue }
                    lastUser = user
                    if let token = await push.currentToken() {
                        await DeviceRegistrar.register(token: token, client: client)
                    }
                    // P3: crew mode and the family's Pro belong to the new user (read before Pro is resolved).
                    await p3.refresh()
                    // RevenueCat follows the Supabase user (contract: logIn(userId) after every sign-in).
                    await pro.identifyAndReload()
                }
            }
        }
        PushBridge.shared.onData = { data in
            Task { @MainActor in await self.handlePush(data) }
        }
        PushBridge.shared.onFlight = { [weak self] id in self?.openFlight(id) }
        PushBridge.shared.onCheckin = { [weak self] url, flightId in
            if let flightId { self?.openFlight(flightId) }
            UIApplication.shared.open(url)
        }
        #if canImport(RevenueCat)
        if let revenueCat = billing as? RevenueCatBilling {
            let pro = pro
            revenueCat.onChange = { Task { await pro.reload() } }
        }
        #endif
        PushBridge.shared.onGmailSuggestion = { [weak self] id, dismiss in self?.openGmailSuggestion(id, dismiss: dismiss) }
        auth.onUserDataWiped = { [weak self] in
            await self?.clearGmail()
            self?.widgets.clear()
            self?.p3.clear()
        }
        // Screens observe the shell; crew mode and the family's Pro live in `p3`.
        p3Changes = p3.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        p4Changes = p4.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        // P3 B12: turning crew mode on cancels pending when-to-leave reminders at once; turning it off re-plans them.
        crewModeChanges = p3.$crewMode.removeDuplicates().dropFirst().sink { [weak self] _ in
            Task { @MainActor in await self?.resyncLeaveReminders() }
        }
        // The widget reads the App Group snapshot the app rewrites after every sync (P3 A4).
        flights.onCacheChanged = { [weak self] in await self?.publishWidgets() }
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
            await alertPrefs.applyDeviceDefaultOnce()
            await p3.refresh()
        }
        await pro.identifyAndReload()
        await FlightLiveActivities.resume(client: stack.client, push: push, flights: flights)
        if let shared = ShareInbox.consume() {
            self.shared.offer(shared)
            path.append(Route.importTicket)
        }
    }

    func open(_ url: URL) {
        // heygate://friend?code=ABCDEFGH from an invite (P2 wave 2 section 1).
        if url.host?.lowercased() == "friend" {
            path.append(Route.acceptInvite(inviteCode(fromURL: url)))
            return
        }
        // heygate://family?code=ABCDEFGH from a family invite (P3 B13).
        if url.host?.lowercased() == "family" {
            path.append(Route.family(familyInviteCode(fromURL: url)))
            return
        }
        // heygate://tripit/callback?code=... (or /connected, /failed?error=...) when a browser handed TripIt's
        // redirect to the app (P4 section 2); ASWebAuthenticationSession normally catches it itself. Settings holds
        // the TripIt row, whose model finishes a code with tripit-complete as the signed-in user.
        if parseTripItCallback(url) != nil {
            p4.pendingTripItCallback = url
            if !p4.settingsVisible { path.append(Route.settings) }
            return
        }
        // heygate://gift?code=ABCD-EFGH-JKMN from a gift message (P4 section 3).
        if url.host?.lowercased() == "gift" {
            path.append(Route.redeemGift(giftCode(fromURL: url)))
            return
        }
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
        await resyncLeaveReminders()
    }

    /// Rewrites the widget snapshot from the cached, tracked flights (P3 A4).
    func publishWidgets() async {
        let tracked = await flights.trackedFlights()
        widgets.publish(flights: tracked.map(\.flight), now: clock.now())
        // P3 B9: the Apple Watch gets the same flights.
        p3.watch.publish(flights: tracked.map(\.flight), now: clock.now())
    }

    /// Re-plans pending "time to leave" reminders from the cache (P2 wave 2 section 5).
    func resyncLeaveReminders() async {
        // P3 B12: crew mode mutes when-to-leave.
        if p3.crewMode {
            await cancelAllLeaveReminders()
            return
        }
        await leaveReminders.resync(flights: flights, travel: travelTimes, pro: await pro.service.proState())
    }

    /// After a purchase or restore the profile changed; screens read the new state from `pro`.
    func proChanged() async {
        await pro.reload()
    }
}

enum Route: Hashable {
    case add(AddFlightPrefill)
    case detail(String)
    case settings
    case importTicket
    case scanBoardingPass
    case gmailImport
    case paywall
    case passport
    case friends
    /// The accept screen, with the code from an invite link when there is one.
    case acceptInvite(String?)
    case forwarding
    /// P3 B4: the connection assistant.
    case connections
    /// P3 B7: delay trends at one airport (IATA).
    case airportTrends(String)
    /// P3 A3: flights found in the device calendar.
    case calendarImport
    /// P3 B10: "You vs {friend}" (friend id, name from the friends list).
    case friendCompare(String, String?)
    /// P3 B13: family sharing, with the code from an invite link when there is one.
    case family(String?)
    /// P4 section 1: ground radar at an airport, planes near me, or planes near a flight.
    case radar(RadarTarget)
    /// P4 section 4: an airport's terminal map, with the gate / belt / terminal to highlight.
    case terminalMap(TerminalMapRequest)
    /// P4 section 3: "Redeem a gift code", with the code from a gift link when there is one.
    case redeemGift(String?)
    case myGifts
    /// P4 section 3: the paywall on its Gift Pro tab.
    case giftPro
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
                    case .paywall:
                        PaywallScreen(shell: shell)
                    case .passport:
                        PassportScreen(shell: shell)
                    case .friends:
                        FriendsScreen(shell: shell)
                    case .acceptInvite(let code):
                        AcceptInviteScreen(shell: shell, prefill: code)
                    case .forwarding:
                        ForwardingScreen(shell: shell)
                    case .connections:
                        ConnectionsScreen(shell: shell)
                    case .airportTrends(let iata):
                        AirportTrendsScreen(shell: shell, iata: iata)
                    case .calendarImport:
                        CalendarImportScreen(shell: shell)
                    case .friendCompare(let id, let name):
                        FriendCompareScreen(shell: shell, friendId: id, friendName: name)
                    case .family(let code):
                        FamilyScreen(shell: shell, prefillCode: code)
                    case .radar(let target):
                        RadarScreen(shell: shell, target: target)
                    case .terminalMap(let request):
                        TerminalMapScreen(shell: shell, request: request)
                    case .redeemGift(let code):
                        RedeemGiftScreen(shell: shell, prefill: code)
                    case .myGifts:
                        MyGiftsScreen(shell: shell)
                    case .giftPro:
                        PaywallScreen(shell: shell, initialTab: .gift)
                    }
                }
        }
        .tint(FlyColor.primary)
        .preferredColorScheme(appearance.colorScheme)
        .task { await shell.bootstrap() }
    }
}
