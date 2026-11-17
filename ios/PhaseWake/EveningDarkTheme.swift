import PhaseWakeCore
import SwiftUI
import UIKit

/// Schlüssel von «Abends dunkel ab» in ``SettingsFile/phone`` (gleiche Namen wie auf Android).
enum EveningDarkKeys {
    static let enabled = "evening_dark"
    static let from = "evening_dark_from"
}

/// Ob «Abends dunkel ab» gerade gilt; gesetzt von ``EveningDarkModifier`` für den Hinweis in den Einstellungen.
private struct EveningDarkActiveKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var eveningDarkActive: Bool {
        get { self[EveningDarkActiveKey.self] }
        set { self[EveningDarkActiveKey.self] = newValue }
    }
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
    @State private var evening = EveningDark.isDark(now: Date(), calendar: .current,
                                                    enabled: EveningDark.defaultEnabled,
                                                    fromMinutes: EveningDark.defaultFromMinutes)
    /// Zählt Uhr- und Zeitzonenwechsel und den Weg in den Vordergrund; startet die Wartezeit neu.
    @State private var clockChanges = 0

    private struct Key: Equatable { let enabled: Bool, from: Int, clock: Int }

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(themeMode.colorScheme(evening: evening))
            .environment(\.eveningDarkActive, evening)
            .task(id: Key(enabled: enabled, from: from, clock: clockChanges)) {
                while !Task.isCancelled {
                    let now = Date()
                    evening = EveningDark.isDark(now: now, calendar: .current, enabled: enabled, fromMinutes: from)
                    guard let next = EveningDark.nextChange(now: now, calendar: .current, enabled: enabled,
                                                            fromMinutes: from)
                    else { return }
                    // Kleiner Zuschlag, damit die Grenzminute sicher erreicht ist.
                    let wait = max(0, next.timeIntervalSince(now)) + 0.05
                    do { try await Task.sleep(for: .seconds(wait)) } catch { return }
                }
            }
            .onChange(of: scenePhase) { _, phase in if phase == .active { clockChanges &+= 1 } }
            .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
                clockChanges &+= 1
            }
            .onReceive(NotificationCenter.default
                .publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
                clockChanges &+= 1
            }
    }
}

/// Abschnitt «Abends dunkel ab» in den Einstellungen: Schalter und Beginn (Ende fest 06:00), wie Android. Gilt die
/// Regel gerade, ein Hinweis «Gilt jetzt – hell ab …» unter dem Schalter.
struct EveningDarkSettingsSection: View {
    @PrivateSetting(EveningDarkKeys.enabled) private var enabled = EveningDark.defaultEnabled
    @PrivateSetting(EveningDarkKeys.from) private var from = EveningDark.defaultFromMinutes
    @Environment(\.eveningDarkActive) private var active
    @Environment(\.colorScheme) private var scheme

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
        let until = Format.time(hour: EveningDark.untilMinutes / 60, minute: EveningDark.untilMinutes % 60)
        Toggle(L("settings_evening_dark"), isOn: $enabled)
        if enabled {
            // Gilt die Regel gerade, bleibt die App auch bei «Hell» dunkel: sagen, warum und bis wann.
            if active {
                Text(L("settings_evening_dark_now", until)).font(.footnote).foregroundStyle(Brand.accent(scheme))
            }
            DatePicker(L("settings_evening_dark_from"), selection: fromDate, displayedComponents: .hourAndMinute)
        }
        Text(L("settings_evening_dark_text", Format.time(hour: m / 60, minute: m % 60), until))
            .font(.footnote).foregroundStyle(.secondary)
    }
}
