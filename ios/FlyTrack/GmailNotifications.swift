import BackgroundTasks
import FlyTrackCore
import Foundation
import UserNotifications

/// Local notifications for new Gmail finds. Each notification's id is its suggestion id, so cancelling needs no lookup.
enum GmailNotifications {
    /// Track opens the app; Not now runs in the background. Registered at launch, before any of these can be tapped.
    static func registerCategory() {
        let track = UNNotificationAction(identifier: GmailNotice.actionTrack, title: String(localized: "Track"), options: [.foreground])
        let notNow = UNNotificationAction(identifier: GmailNotice.actionNotNow, title: String(localized: "Not now"), options: [])
        let category = UNNotificationCategory(identifier: GmailNotice.category, actions: [track, notNow], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// One notification per new suggestion. Without notification permission `add` fails and only the card shows.
    static func post(_ suggestions: [GmailSuggestion]) async {
        let center = UNUserNotificationCenter.current()
        for suggestion in suggestions {
            let content = UNMutableNotificationContent()
            content.body = GmailNotice.body(suggestion)
            content.sound = .default
            content.categoryIdentifier = GmailNotice.category
            content.threadIdentifier = GmailNotice.threadId
            content.userInfo = [GmailNotice.keySuggestionId: suggestion.id]
            try? await center.add(UNNotificationRequest(identifier: suggestion.id, content: content, trigger: nil))
        }
    }

    static func cancel(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    /// Disconnect and sign-out: removes every Gmail notification and leaves flight alerts alone.
    static func cancelAll() async {
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications().map(\.request)
        let pending = await center.pendingNotificationRequests()
        cancel((delivered + pending).filter { $0.content.categoryIdentifier == GmailNotice.category }.map(\.identifier))
    }
}

/// The automatic Gmail check: on app open (at most hourly) and as an opportunistic BGAppRefreshTask.
@MainActor
enum GmailBackground {
    private static var running = false

    /// Called from `application(_:didFinishLaunchingWithOptions:)`: registering after launch finishes crashes,
    /// and `AppShell.bootstrap()` does not run on a background launch.
    static func register(shell: AppShell) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: GmailNotice.backgroundTaskId, using: .main) { task in
            let work = Task { @MainActor in
                await check(shell: shell)
                task.setTaskCompleted(success: !Task.isCancelled)
            }
            // Cancelling stops the scan early, like the 50-message cap: lastCheck stays, seen ids are kept.
            task.expirationHandler = { @Sendable in work.cancel() }
        }
    }

    /// Replaces any pending request. Fails on the simulator and when Background App Refresh is off; the app-open check still runs.
    static func submit() {
        let request = BGAppRefreshTaskRequest(identifier: GmailNotice.backgroundTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(GmailNotice.appOpenThrottleSeconds))
        try? BGTaskScheduler.shared.submit(request)
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: GmailNotice.backgroundTaskId)
    }

    /// Scene became active. A scan from the Gmail screen is separate and always runs.
    static func appOpened(shell: AppShell) async {
        guard await enabled(shell.settings) else { return cancel() }
        submit()
        let now = Int64(shell.clock.now().timeIntervalSince1970)
        guard GmailNotice.appOpenCheckDue(lastCheck: await shell.settings.gmailLastAppOpenCheck(), now: now) else { return }
        await shell.settings.setGmailLastAppOpenCheck(now)
        await check(shell: shell)
    }

    /// Cleanup, scan, one notification per new find, then book the next background run.
    static func check(shell: AppShell) async {
        guard !running else { return }
        guard await enabled(shell.settings) else { return cancel() }
        running = true
        defer { running = false }
        let scanner = shell.gmailScanner()
        let cleaned = await scanner.cleanup()
        GmailNotifications.cancel(cleaned.deleteIds + cleaned.markAddedIds)
        let outcome = await scanner.scan()
        await GmailNotifications.post(outcome.newSuggestions)
        submit()
    }

    /// Connected, Auto-check on, and access not lost: background checks stop until the user reconnects.
    private static func enabled(_ settings: DeviceSettings) async -> Bool {
        guard await settings.gmailAccountEmail() != nil, await settings.gmailAutoCheck() else { return false }
        return await !settings.gmailNeedsReconnect()
    }
}
