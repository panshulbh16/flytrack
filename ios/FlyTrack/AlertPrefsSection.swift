import FlyTrackCore
import SwiftUI

/// Settings → quiet hours and "Language for alerts" (P2 contract sections 3 and 4). Stored on the server in
/// `profiles`, so every phone of the account and the backend's renderer use the same choice. The settings screen
/// owns the store and loads it; the sections stay hidden until a profile is there.
struct AlertPrefsSection: View {
    var store: AlertPrefsStore

    var body: some View {
        if store.state.available {
            quietHours(store)
                .listRowBackground(FlyColor.paperRaised)
            language(store)
                .listRowBackground(FlyColor.paperRaised)
        }
    }

    private func quietHours(_ store: AlertPrefsStore) -> some View {
        let prefs = store.state.prefs
        return Section {
            Toggle(String(localized: "Quiet hours"), isOn: Binding(
                get: { prefs.quietHoursEnabled },
                set: { value in Task { await store.apply(.quietHoursEnabled(value)) } }
            ))
            .disabled(store.state.isSaving)
            if prefs.quietHoursEnabled {
                DatePicker(String(localized: "From"), selection: Binding(
                    get: { date(prefs.quietStart) },
                    set: { value in Task { await store.apply(.quietStart(clockTime(value))) } }
                ), displayedComponents: .hourAndMinute)
                DatePicker(String(localized: "Until"), selection: Binding(
                    get: { date(prefs.quietEnd) },
                    set: { value in Task { await store.apply(.quietEnd(clockTime(value))) } }
                ), displayedComponents: .hourAndMinute)
                if prefs.quietTz != TimeZone.current.identifier {
                    Text(String(localized: "Times are in \(prefs.quietTz). Change them to use this phone's time zone."))
                        .font(FlyFont.sans(13, relativeTo: .footnote))
                        .foregroundStyle(FlyColor.muted)
                }
            }
            if let message = store.state.message {
                Text(messageText(message))
                    .font(FlyFont.sans(13, relativeTo: .footnote))
                    .foregroundStyle(FlyColor.cancelled)
            }
        } header: {
            Text(String(localized: "Quiet hours"))
        } footer: {
            Text(String(localized: "During quiet hours, updates like take-off and landing arrive silently. Gate changes, delays, cancellations and fog alerts always make a sound."))
        }
    }

    private func language(_ store: AlertPrefsStore) -> some View {
        Section {
            Picker(String(localized: "Language for alerts"), selection: Binding(
                get: { store.state.prefs.language },
                set: { value in Task { await store.apply(.language(value)) } }
            )) {
                ForEach(AlertLanguage.allCases, id: \.self) { language in
                    Text(languageName(language)).tag(language)
                }
            }
            .disabled(store.state.isSaving)
        } header: {
            Text(String(localized: "Language for alerts"))
        } footer: {
            Text(String(localized: "Flight numbers, airport codes and times stay in English letters and digits."))
        }
    }

    /// Today at that wall-clock time on this phone, for the DatePicker.
    private func date(_ time: ClockTime) -> Date {
        Calendar.current.date(bySettingHour: time.hour, minute: time.minute, second: 0, of: Date()) ?? Date()
    }

    private func clockTime(_ date: Date) -> ClockTime {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        return ClockTime(hour: parts.hour ?? 0, minute: parts.minute ?? 0)
    }

    private func messageText(_ message: AlertPrefsMessage) -> String {
        switch message {
        case .offline: String(localized: "You're offline. The change was not saved.")
        case .saveFailed: String(localized: "Couldn't save. Try again.")
        case .loadFailed: String(localized: "Couldn't load your alert settings.")
        }
    }
}

/// Shown in the language's own script, so a reader finds it whatever the app language.
private func languageName(_ language: AlertLanguage) -> String {
    switch language {
    case .en: "English"
    case .hi: "हिन्दी"
    case .hinglish: "Hinglish"
    }
}

@MainActor
@Observable
final class AlertPrefsStore {
    var state: AlertPrefsState
    let model: AlertPrefsModel
    init(model: AlertPrefsModel) {
        self.model = model
        state = model.state
        model.onChange = { [weak self] in self?.state = $0 }
    }
    func load() async { await model.load(); state = model.state }
    func apply(_ change: AlertPrefsChange) async { await model.apply(change); state = model.state }
}
