import PhaseWakeCore
import SwiftUI
import UIKit

/// Schlüssel von «Abends dunkel ab» in ``SettingsFile/phone`` (gleiche Namen wie auf Android).
enum EveningDarkKeys {
    static let enabled = "evening_dark"
    static let from = "evening_dark_from"
}

extension ThemeMode {
    /// Design mit «Abends dunkel ab», wie `resolveDark` auf Android: «Dunkel» bleibt dunkel; abends werden «Hell» und
    /// «System» dunkel; sonst gilt die Wahl (`nil` = wie das System).
    func colorScheme(evening: Bool) -> ColorScheme? {
        evening ? .dark : colorScheme
    }
}

/// Ob gerade «abends dunkel» gilt. Rechnet sofort neu, wenn sich die Einstellung ändert, und schläft sonst genau bis
/// zum nächsten Wechsel (``EveningDark/nextChange(now:calendar:enabled:fromMinutes:)``), statt minütlich nachzusehen;
/// nach Rückkehr in den Vordergrund, Zeitzonen- oder Uhrwechsel ebenfalls neu. Wie `EveningDarkTicker` auf Android.
struct EveningDarkModifier: ViewModifier {
    let themeMode: ThemeMode
    @PrivateSetting(EveningDarkKeys.enabled) private var enabled = EveningDark.defaultEnabled
    @PrivateSetting(EveningDarkKeys.from) private var from = EveningDark.defaultFromMinutes
    @Environment(\.scenePhase) private var scenePhase
    @State private var evening = EveningDark.isDark(now: Date(), calendar: .current, enabled: EveningDark.defaultEnabled,
                                                    fromMinutes: EveningDark.defaultFromMinutes)
    /// Zählt Uhr- und Zeitzonenwechsel und den Weg in den Vordergrund; startet die Wartezeit neu.
    @State private var clockChanges = 0

    private struct Key: Equatable { let enabled: Bool, from: Int, clock: Int }

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(themeMode.colorScheme(evening: evening))
            .task(id: Key(enabled: enabled, from: from, clock: clockChanges)) {
                while !Task.isCancelled {
                    let now = Date()
                    evening = EveningDark.isDark(now: now, calendar: .current, enabled: enabled, fromMinutes: from)
                    guard let next = EveningDark.nextChange(now: now, calendar: .current, enabled: enabled, fromMinutes: from)
                    else { return }
                    // Kleiner Zuschlag, damit die Grenzminute sicher erreicht ist.
                    let wait = max(0, next.timeIntervalSince(now)) + 0.05
                    do { try await Task.sleep(for: .seconds(wait)) } catch { return }
                }
            }
            .onChange(of: scenePhase) { _, phase in if phase == .active { clockChanges &+= 1 } }
            .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in clockChanges &+= 1 }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in clockChanges &+= 1 }
    }
}

/// Abschnitt «Abends dunkel ab» in den Einstellungen: Schalter und Beginn (Ende fest 06:00), wie Android.
struct EveningDarkSettingsSection: View {
    @PrivateSetting(EveningDarkKeys.enabled) private var enabled = EveningDark.defaultEnabled
    @PrivateSetting(EveningDarkKeys.from) private var from = EveningDark.defaultFromMinutes

    private var fromDate: Binding<Date> {
        Binding(
            get: {
                let m = EveningDark.checkedFrom(from)
                return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
            },
            set: {
                let c = Calendar.current.dateComponents([.hour, .minute], from: $0)
                from = EveningDark.checkedFrom((c.hour ?? 0) * 60 + (c.minute ?? 0))
            }
        )
    }

    var body: some View {
        let m = EveningDark.checkedFrom(from)
        Toggle(L("settings_evening_dark"), isOn: $enabled)
        if enabled {
            DatePicker(L("settings_evening_dark_from"), selection: fromDate, displayedComponents: .hourAndMinute)
        }
        Text(L("settings_evening_dark_text", Format.time(hour: m / 60, minute: m % 60),
               Format.time(hour: EveningDark.untilMinutes / 60, minute: EveningDark.untilMinutes % 60)))
            .font(.footnote).foregroundStyle(.secondary)
    }
}
