import PhaseWakeCore
import SwiftUI
import UIKit
import UserNotifications
import WatchConnectivity
#if canImport(AlarmKit)
import AlarmKit
#endif

/// Nacht-Check: Was muss stimmen, damit morgen sicher geweckt wird? Gleiche Idee wie Android.
enum PhoneCheck: CaseIterable, Hashable {
    case watch, watchApp, watchArm, watchBattery, alarms, notifications, battery
    /// Uhr nicht scharf, obwohl die Erinnerung am Abend schon kam (60 Min vor der Bettzeit, `EveningReminder` auf der
    /// Uhr): Heute Nacht weckt sie dann nur per Mitteilung, ohne Schlafphasen.
    case watchArmTonight
    /// Uhr-App älter als die iPhone-App (`WatchProtocol`).
    case watchOutdated
    /// Zweitwecker bald aufgebraucht: iOS hat die App zu lange nicht geweckt (`BackupAlarm.horizonShort`).
    case backupHorizon
    /// Vor iOS 26 ist der Zweitwecker eine Mitteilung: Der Stummschalter macht ihn lautlos. Nur ein Hinweis.
    case silentSwitch
    /// Mitteilungen auf der Uhr nicht (zeitkritisch) erlaubt: Ohne laufende Messung weckt dann der Rückfall nicht.
    case watchNotifications
    /// Stand der Mitteilungen auf der Uhr älter als 3 Tage (`WatchNotifications.isOutdated`). Nur ein Hinweis.
    case watchStateOld
    /// Die Uhr-App lief, der Rekorder startete für diese Nacht aber nicht, obwohl Einschlaf-Messung oder Lernphase ihn
    /// wollten (``RecorderSkip``, Audit F-06, wie Android): Akku zu schwach bzw. keine Erlaubnis für Bewegungsdaten.
    case watchRecorderBattery, watchRecorderMotion

    var title: String {
        switch self {
        case .watch: L("check_watch")
        case .watchApp: L("check_watch_app")
        case .watchArm, .watchArmTonight: L("check_watch_arm")
        case .watchBattery: L("check_watch_battery")
        case .watchOutdated: L("check_watch_outdated_title")
        case .alarms: L("check_alarmkit")
        case .notifications: L("check_notifications")
        case .battery: L("check_battery")
        case .backupHorizon: L("check_backup_horizon_title")
        case .silentSwitch: L("check_silent_switch_title")
        case .watchNotifications, .watchStateOld: L("check_watch_notifications")
        case .watchRecorderBattery, .watchRecorderMotion: L("check_watch_recorder")
        }
    }

    var problem: String {
        switch self {
        case .watch: L("check_watch_problem_apple")
        case .watchApp: L("check_watch_app_problem")
        case .watchArm: L("check_watch_arm_problem")
        case .watchArmTonight: L("check_watch_arm_tonight")
        case .watchBattery: L("check_watch_battery_problem", NightCheck.lowBatteryPercent)
        case .watchOutdated: L("check_watch_outdated_text")
        case .alarms: L("check_alarmkit_problem")
        case .notifications: L("check_notifications_problem")
        case .battery: L("check_battery_problem", NightCheck.lowBatteryPercent)
        case .backupHorizon: L("check_backup_horizon_text")
        case .silentSwitch: L("check_silent_switch_text")
        case .watchNotifications: L("check_watch_notifications_problem")
        case .watchStateOld: L("check_watch_state_old")
        case .watchRecorderBattery: L("check_watch_recorder_battery")
        case .watchRecorderMotion: L("check_watch_recorder_motion")
        }
    }

    var canFix: Bool { self == .alarms || self == .notifications }

    /// Nur ein Hinweis (nicht rot, «bereit» bleibt): Er lässt sich nicht beheben, nur beachten.
    var isAdvisory: Bool { self == .silentSwitch || self == .watchStateOld }
}

@MainActor
enum NightCheck {
    nonisolated static let lowBatteryPercent = 20
    /// So weit im Voraus nimmt watchOS eine Smart-Alarm-Sitzung an (`SmartAlarm.maxLeadTime` auf der Uhr).
    private static let armLeadTime: TimeInterval = 36 * 3600
    /// Zuletzt gemeldet: Rekorder für eine Nacht nicht gestartet (`PhoneConnectivity`); `nil` = keine Meldung.
    static var watchRecorderSkip: RecorderSkip?

    static func collect() async -> [PhoneCheck] {
        let session = WCSession.isSupported() ? WCSession.default : nil
        let model = PhoneModel.shared
        // Wie auf der Uhr: Eine heute schon abgeschlossene Nacht zählt nicht mehr.
        let plan = model.nextPlan()
        let watch = WatchState(
            paired: session?.activationState == .activated && session?.isPaired == true,
            appInstalled: session?.isWatchAppInstalled == true,
            plan: plan,
            armedTarget: model.watchArmedTarget,
            reminder: plan.map { reminderTime($0, model: model) },
            battery: model.watchBattery(),
            recorderSkip: watchRecorderSkip,
            notifications: model.watchNotifications,
            watchProtocol: model.watchProtocol
        )
        var failed = watchChecks(watch, now: Date())
        if await !alarmsAllowed() { failed.append(.alarms) }
        if await !notificationsAllowed() { failed.append(.notifications) }
        if await model.backup.horizonShort(model.alarms) { failed.append(.backupHorizon) }
        UIDevice.current.isBatteryMonitoringEnabled = true
        if phoneBatteryLow(unplugged: UIDevice.current.batteryState == .unplugged, level: UIDevice.current.batteryLevel) {
            failed.append(.battery)
        }
        if !hasAlarmKit { failed.append(.silentSwitch) }
        return failed
    }

    /// Was über die Uhr bekannt ist (``watchChecks(_:now:)``).
    struct WatchState {
        /// Apple Watch gekoppelt, Verbindung aktiv.
        var paired: Bool
        /// Phase Wake auf der Uhr installiert.
        var appInstalled: Bool
        /// Nächste Nacht; `nil` ohne Wecker.
        var plan: WakePlan?
        /// Zielzeit, für die die Uhr scharf ist (`PhoneModel.watchArmedTarget`).
        var armedTarget: Date?
        /// Erinnerung am Abend der Uhr für `plan` (``reminderTime(_:model:)``).
        var reminder: Date?
        /// Akkustand der Uhr in Prozent; `nil`, wenn unbekannt oder veraltet.
        var battery: Int?
        /// Rekorder für eine Nacht nicht gestartet (``watchRecorderSkip``).
        var recorderSkip: RecorderSkip?
        /// Erlaubnis für Mitteilungen auf der Uhr und Zeitpunkt ihrer Prüfung; `nil`, wenn unbekannt.
        var notifications: (state: WatchNotifications, checkedAt: Date?)?
        /// Stand der Verbindung der Uhr-App (``WatchProtocol``); `nil`, wenn unbekannt.
        var watchProtocol: Int?
    }

    /// Prüfungen der Uhr zum Zeitpunkt `now` (ohne Systemabfragen, damit sie sich testen lassen).
    static func watchChecks(_ watch: WatchState, now: Date) -> [PhoneCheck] {
        guard watch.paired else { return [.watch] }
        guard watch.appInstalled else { return [.watchApp] }
        var failed: [PhoneCheck] = []
        // watchOS plant den Smart Alarm nur bei offener Uhr-App und höchstens 36 h im Voraus.
        // Hat die Uhr noch nie gemeldet (App dort nie geöffnet), ist sie auch nicht scharf.
        // Weiter voraus lässt es sich noch nicht scharf schalten, also auch nicht warnen.
        // Ab der Erinnerung am Abend (60 Min vor der Bettzeit, wie auf der Uhr) deutlicher: heute Nacht ohne Phasen.
        if let plan = watch.plan, plan.windowStart.timeIntervalSince(now) < armLeadTime, watch.armedTarget != plan.target {
            failed.append(watch.reminder.map { now >= $0 } == true ? .watchArmTonight : .watchArm)
        }
        if let percent = watch.battery, percent < lowBatteryPercent {
            failed.append(.watchBattery)
        }
        // Rekorder heute Nacht nicht gestartet: bis zur Zielzeit dieser Nacht.
        if let skip = watch.recorderSkip, skip.applies(at: now) {
            failed.append(skip.reason == .battery ? .watchRecorderBattery : .watchRecorderMotion)
        }
        // Wie die Uhr zuletzt gemeldet hat; unbekannt (ältere Uhr, noch nicht gefragt): keine Warnung.
        if watch.notifications?.state.isProblem == true { failed.append(.watchNotifications) }
        // Hat die Uhr ihn seit über 3 Tagen nicht mehr geprüft, kann er überholt sein (ohne Zeitpunkt: kein Hinweis).
        if WatchNotifications.isOutdated(checkedAt: watch.notifications?.checkedAt, now: now) {
            failed.append(.watchStateOld)
        }
        // Ältere Uhr-App: Sie meldet einen älteren Stand der Verbindung (ohne Angabe 0); noch kein Kontext: keine Warnung.
        if WatchProtocol.isOutdated(watch: watch.watchProtocol) { failed.append(.watchOutdated) }
        return failed
    }

    /// iPhone-Akku zu schwach für die Nacht: nicht am Strom und unter ``lowBatteryPercent`` (`level` von `UIDevice`,
    /// 0 bis 1, negativ = unbekannt).
    static func phoneBatteryLow(unplugged: Bool, level: Float) -> Bool {
        unplugged && level >= 0 && level * 100 < Float(lowBatteryPercent)
    }

    /// Erinnerung am Abend der Uhr für `plan`: 60 Min vor der Bettzeit (Zielzeit minus Schlafdauer bzw. Schlafziel).
    private static func reminderTime(_ plan: WakePlan, model: PhoneModel) -> Date {
        let spec = model.alarms.first { $0.id == plan.alarmId }
        return ShortNight.autoOnsetStart(target: plan.target, sleepGoalMinutes: model.sleepGoal, sleepDurationMinutes: spec?.sleepDurationMinutes)
    }

    /// Ob der Zweitwecker über AlarmKit klingelt (ab iOS 26) und nicht als Mitteilung.
    private static var hasAlarmKit: Bool {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) { return true }
        #endif
        return false
    }

    /// Ab iOS 26 klingelt der Zweitwecker über AlarmKit; davor prüft nur der Mitteilungs-Check.
    private static func alarmsAllowed() async -> Bool {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) { return AlarmManager.shared.authorizationState == .authorized }
        #endif
        return true
    }

    private static func notificationsAllowed() async -> Bool {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) { return true }
        #endif
        // Zeitkritisch aus: Der Zweitwecker (Mitteilung) käme durch einen Fokus nicht durch – so ernst wie keine Erlaubnis.
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        let status = settings.authorizationStatus
        return (status == .authorized || status == .provisional) && settings.timeSensitiveSetting != .disabled
    }

    /// Fragt die Erlaubnis an; wurde sie schon abgelehnt, öffnet es die Einstellungen.
    static func fix(_ check: PhoneCheck) async {
        switch check {
        case .alarms:
            #if canImport(AlarmKit)
            if #available(iOS 26.0, *) {
                if AlarmManager.shared.authorizationState == .notDetermined {
                    do {
                        _ = try await AlarmManager.shared.requestAuthorization()
                    } catch {
                        NotificationLog.permissionFailed("alarmkit", error)
                    }
                    return
                }
            }
            #endif
            openSettings()
        case .notifications:
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            if status == .notDetermined {
                await NotificationLog.requestPermission("night check")
            } else {
                openSettings()
            }
        case .watch, .watchApp, .watchArm, .watchArmTonight, .watchBattery, .watchOutdated, .battery, .backupHorizon, .silentSwitch,
             .watchNotifications, .watchStateOld, .watchRecorderBattery, .watchRecorderMotion:
            break
        }
    }

    private static func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// Karte auf der Startseite: knapp, wenn alles stimmt; sonst jede offene Prüfung mit Knopf. Hinweise
/// (`isAdvisory`) stehen dabei, ohne die Karte rot zu machen.
struct NightCheckCard: View {
    let failed: [PhoneCheck]
    let onFix: (PhoneCheck) -> Void
    @State private var expanded = false
    @Environment(\.colorScheme) private var scheme

    /// Prüfungen, die das Wecken gefährden (ohne Hinweise).
    private var blocking: [PhoneCheck] { failed.filter { !$0.isAdvisory } }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button { if blocking.isEmpty { withAnimation { expanded.toggle() } } } label: {
                HStack(spacing: 12) {
                    Image(systemName: blocking.isEmpty ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                        .foregroundStyle(blocking.isEmpty ? Brand.accent(scheme) : .red)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L(blocking.isEmpty ? "check_ready" : "check_not_ready")).font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                        if blocking.isEmpty {
                            Text(L("check_ready_text")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme))
                        }
                    }
                    Spacer()
                }
            }
            .buttonStyle(.plain)
            .disabled(!blocking.isEmpty)
            if blocking.isEmpty && expanded {
                ForEach([PhoneCheck.watch, .watchApp, .alarms, .battery], id: \.self) { check in
                    Label(check.title, systemImage: "checkmark").font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
                }
            }
            ForEach(failed, id: \.self) { check in
                HStack(alignment: .center) {
                    // Zeichen statt nur Farbe (WCAG 1.4.1): ⚠ = offen, wie Android; ⓘ = nur ein Hinweis.
                    Image(systemName: check.isAdvisory ? "info.circle" : "exclamationmark.triangle")
                        .foregroundStyle(check.isAdvisory ? Brand.secondaryText(scheme) : .red)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(check.title).font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
                        Text(check.problem).font(Fonts.manrope(14, relativeTo: .subheadline))
                    }
                    Spacer()
                    if check.canFix {
                        Button(L("check_allow")) { onFix(check) }
                            .buttonStyle(.borderedProminent)
                            .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                    }
                }
            }
            // Probewecker: prüft Uhr, iPhone und Verbindung in 2 Minuten.
            TestAlarmButton().font(Fonts.manrope(14, relativeTo: .subheadline)).padding(.top, 4)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(blocking.isEmpty ? Brand.card(scheme) : Color.red.opacity(0.14), in: RoundedRectangle(cornerRadius: Brand.cardRadius))
        .padding(.horizontal, 16)
    }
}
