import PhaseWakeCore
import SwiftUI
import UIKit

/// Schlüssel von «Abends dunkel» in ``SettingsFile/phone`` (gleiche Namen wie auf Android).
enum EveningDarkKeys {
    static let enabled = "evening_dark"
    static let from = "evening_dark_from"
    static let until = "evening_dark_until"
}

/// Ob «Abends dunkel» gerade etwas bewirkt (``ThemeMode/eveningChangesTheme(evening:systemDark:)``); gesetzt von
/// ``EveningDarkModifier`` für den Hinweis in den Einstellungen.
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
    /// Design mit «Abends dunkel», wie `resolveDark` auf Android: «Dunkel» bleibt dunkel; abends werden «Hell» und
    /// «System» dunkel; sonst gilt die Wahl (`nil` = wie das System).
    func colorScheme(evening: Bool) -> ColorScheme? {
        evening ? .dark : colorScheme
    }

    /// Ob «Abends dunkel» gerade etwas bewirkt, wie `eveningChangesTheme` auf Android: Die Regel gilt, und ohne sie
    /// wäre die App hell («Hell», oder «System» bei hellem System). Bei «System» mit dunklem System bliebe die App
    /// ohnehin dunkel; dann kein Hinweis «Gilt jetzt».
    func eveningChangesTheme(evening: Bool, systemDark: Bool) -> Bool {
        switch self {
        case .dark: false
        case .light: evening
        case .system: evening && !systemDark
        }
    }
}

/// Ob das System gerade dunkel ist, unabhängig vom Design der App: Die App überschreibt den Stil ihres Fensters
/// (`preferredColorScheme`), nicht den der Szene. Folgt Änderungen am Systemstil (auch automatisch am Abend).
@MainActor
final class SystemStyle: ObservableObject {
    @Published private(set) var isDark = false
    private var registration: (any UITraitChangeRegistration)?

    /// Liest den Stil der Szene und beobachtet ihn ab jetzt; mehrfach aufrufbar.
    func attach() {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            return
        }
        isDark = scene.traitCollection.userInterfaceStyle == .dark
        guard registration == nil else { return }
        registration = scene.registerForTraitChanges([UITraitUserInterfaceStyle.self]) {
            [weak self] (changed: UIWindowScene, _: UITraitCollection) in
            MainActor.assumeIsolated { self?.isDark = changed.traitCollection.userInterfaceStyle == .dark }
        }
    }
}

/// Ob gerade «abends dunkel» gilt. Rechnet sofort neu, wenn sich die Einstellung ändert, und schläft sonst genau bis
/// zum nächsten Wechsel (``EveningDark/nextChange(now:calendar:enabled:fromMinutes:untilMinutes:)``), statt minütlich
/// nachzusehen; nach Rückkehr in den Vordergrund, Zeitzonen- oder Uhrwechsel ebenfalls neu. Wie `EveningDarkTicker` auf
/// Android. Setzt ``EnvironmentValues/eveningDarkActive`` mit dem Systemstil aus ``SystemStyle``.
struct EveningDarkModifier: ViewModifier {
    let themeMode: ThemeMode
    @PrivateSetting(EveningDarkKeys.enabled) private var enabled = EveningDark.defaultEnabled
    @PrivateSetting(EveningDarkKeys.from) private var from = EveningDark.defaultFromMinutes
    @PrivateSetting(EveningDarkKeys.until) private var until = EveningDark.defaultUntilMinutes
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var system = SystemStyle()
    @State private var evening = EveningDark.isDark(now: Date(), calendar: .current,
                                                    enabled: EveningDark.defaultEnabled,
                                                    fromMinutes: EveningDark.defaultFromMinutes,
                                                    untilMinutes: EveningDark.defaultUntilMinutes)
    /// Zählt Uhr- und Zeitzonenwechsel und den Weg in den Vordergrund; startet die Wartezeit neu.
    @State private var clockChanges = 0

    private struct Key: Equatable { let enabled: Bool, from: Int, until: Int, clock: Int }

    func body(content: Content) -> some View {
        content
            .preferredColorScheme(themeMode.colorScheme(evening: evening))
            .environment(\.eveningDarkActive,
                         themeMode.eveningChangesTheme(evening: evening, systemDark: system.isDark))
            .onAppear { system.attach() }
            .task(id: Key(enabled: enabled, from: from, until: until, clock: clockChanges)) {
                while !Task.isCancelled {
                    let now = Date()
                    evening = EveningDark.isDark(now: now, calendar: .current, enabled: enabled, fromMinutes: from,
                                                 untilMinutes: until)
                    guard let next = EveningDark.nextChange(now: now, calendar: .current, enabled: enabled,
                                                            fromMinutes: from, untilMinutes: until)
                    else { return }
                    // Kleiner Zuschlag, damit die Grenzminute sicher erreicht ist.
                    let wait = max(0, next.timeIntervalSince(now)) + 0.05
                    do { try await Task.sleep(for: .seconds(wait)) } catch { return }
                }
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active else { return }
                clockChanges &+= 1
                system.attach()
            }
            .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
                clockChanges &+= 1
            }
            .onReceive(NotificationCenter.default
                .publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
                clockChanges &+= 1
            }
    }
}

/// Abschnitt «Abends dunkel» in den Einstellungen: Schalter, «Dunkel ab» und «Hell ab», wie Android. Gleiche Zeiten:
/// nie dunkel, das sagt die Fussnote. Bewirkt die Regel gerade etwas, ein Hinweis «Gilt jetzt – hell ab …» mit der
/// eingestellten Endzeit unter dem Schalter.
struct EveningDarkSettingsSection: View {
    @PrivateSetting(EveningDarkKeys.enabled) private var enabled = EveningDark.defaultEnabled
    @PrivateSetting(EveningDarkKeys.from) private var from = EveningDark.defaultFromMinutes
    @PrivateSetting(EveningDarkKeys.until) private var until = EveningDark.defaultUntilMinutes
    @Environment(\.eveningDarkActive) private var active
    @Environment(\.colorScheme) private var scheme

    /// Minuten des Tages als Datum für den Zeitwähler; `check` ersetzt einen unplausiblen Wert durch den Standard.
    private func time(_ minutes: Binding<Int>, check: @escaping @Sendable (Int?) -> Int) -> Binding<Date> {
        Binding(
            get: {
                let m = check(minutes.wrappedValue)
                return Calendar.current.date(bySettingHour: m / 60, minute: m % 60, second: 0, of: Date()) ?? Date()
            },
            set: {
                let c = Calendar.current.dateComponents([.hour, .minute], from: $0)
                minutes.wrappedValue = check((c.hour ?? 0) * 60 + (c.minute ?? 0))
            }
        )
    }

    var body: some View {
        let start = EveningDark.checkedFrom(from)
        let end = EveningDark.checkedUntil(until)
        let endText = Format.time(hour: end / 60, minute: end % 60)
        Toggle(L("settings_evening_dark"), isOn: $enabled)
        if enabled {
            // Bewirkt die Regel gerade etwas, ist die App dunkel, obwohl sie sonst hell wäre: sagen, warum, bis wann.
            if active {
                Text(L("settings_evening_dark_now", endText)).font(.footnote).foregroundStyle(Brand.accent(scheme))
            }
            DatePicker(L("settings_evening_dark_from"), selection: time($from, check: { EveningDark.checkedFrom($0) }),
                       displayedComponents: .hourAndMinute)
            DatePicker(L("settings_evening_dark_until"),
                       selection: time($until, check: { EveningDark.checkedUntil($0) }),
                       displayedComponents: .hourAndMinute)
        }
        Text(start == end ? L("settings_evening_dark_same")
             : L("settings_evening_dark_text", Format.time(hour: start / 60, minute: start % 60), endText))
            .font(.footnote).foregroundStyle(.secondary)
    }
}
