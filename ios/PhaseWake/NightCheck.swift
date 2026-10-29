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

    var title: String {
        switch self {
        case .watch: L("check_watch")
        case .watchApp: L("check_watch_app")
        case .watchArm: L("check_watch_arm")
        case .watchBattery: L("check_watch_battery")
        case .alarms: L("check_alarmkit")
        case .notifications: L("check_notifications")
        case .battery: L("check_battery")
        }
    }

    var problem: String {
        switch self {
        case .watch: L("check_watch_problem_apple")
        case .watchApp: L("check_watch_app_problem")
        case .watchArm: L("check_watch_arm_problem")
        case .watchBattery: L("check_watch_battery_problem", NightCheck.lowBatteryPercent)
        case .alarms: L("check_alarmkit_problem")
        case .notifications: L("check_notifications_problem")
        case .battery: L("check_battery_problem", NightCheck.lowBatteryPercent)
        }
    }

    var canFix: Bool { self == .alarms || self == .notifications }
}

@MainActor
enum NightCheck {
    nonisolated static let lowBatteryPercent = 20
    /// So weit im Voraus nimmt watchOS eine Smart-Alarm-Sitzung an (`SmartAlarm.maxLeadTime` auf der Uhr).
    private static let armLeadTime: TimeInterval = 36 * 3600

    static func collect() async -> [PhoneCheck] {
        var failed: [PhoneCheck] = []
        let session = WCSession.isSupported() ? WCSession.default : nil
        let model = PhoneModel.shared
        // Wie auf der Uhr: Eine heute schon abgeschlossene Nacht zählt nicht mehr.
        let plan = AlarmSchedule.earliestPlan(model.alarms, now: Date(), doneUntil: model.doneUntil, calendar: .current)
        if session?.activationState != .activated || session?.isPaired != true {
            failed.append(.watch)
        } else if session?.isWatchAppInstalled != true {
            failed.append(.watchApp)
        } else {
            // watchOS plant den Smart Alarm nur bei offener Uhr-App und höchstens 36 h im Voraus.
            // Hat die Uhr noch nie gemeldet (App dort nie geöffnet), ist sie auch nicht scharf.
            // Weiter voraus lässt es sich noch nicht scharf schalten, also auch nicht warnen.
            if let plan, plan.windowStart.timeIntervalSinceNow < armLeadTime, model.watchArmedTarget != plan.target {
                failed.append(.watchArm)
            }
            if let percent = model.watchBattery(), percent < lowBatteryPercent {
                failed.append(.watchBattery)
            }
        }
        if await !alarmsAllowed() { failed.append(.alarms) }
        if await !notificationsAllowed() { failed.append(.notifications) }
        UIDevice.current.isBatteryMonitoringEnabled = true
        let level = UIDevice.current.batteryLevel
        if UIDevice.current.batteryState == .unplugged, level >= 0, level * 100 < Float(lowBatteryPercent) {
            failed.append(.battery)
        }
        return failed
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
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    /// Fragt die Erlaubnis an; wurde sie schon abgelehnt, öffnet es die Einstellungen.
    static func fix(_ check: PhoneCheck) async {
        switch check {
        case .alarms:
            #if canImport(AlarmKit)
            if #available(iOS 26.0, *) {
                if AlarmManager.shared.authorizationState == .notDetermined {
                    _ = try? await AlarmManager.shared.requestAuthorization()
                    return
                }
            }
            #endif
            openSettings()
        case .notifications:
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            if status == .notDetermined {
                _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
            } else {
                openSettings()
            }
        case .watch, .watchApp, .watchArm, .watchBattery, .battery:
            break
        }
    }

    private static func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// Karte auf der Startseite: knapp, wenn alles stimmt; sonst jede offene Prüfung mit Knopf.
struct NightCheckCard: View {
    let failed: [PhoneCheck]
    let onFix: (PhoneCheck) -> Void
    @State private var expanded = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button { if failed.isEmpty { withAnimation { expanded.toggle() } } } label: {
                HStack(spacing: 12) {
                    Image(systemName: failed.isEmpty ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                        .foregroundStyle(failed.isEmpty ? Brand.accent(scheme) : .red)
                        .font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L(failed.isEmpty ? "check_ready" : "check_not_ready")).font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                        if failed.isEmpty {
                            Text(L("check_ready_text")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme))
                        }
                    }
                    Spacer()
                }
            }
            .buttonStyle(.plain)
            .disabled(!failed.isEmpty)
            if failed.isEmpty && expanded {
                ForEach([PhoneCheck.watch, .watchApp, .alarms, .battery], id: \.self) { check in
                    Label(check.title, systemImage: "checkmark").font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
                }
            }
            ForEach(failed, id: \.self) { check in
                HStack(alignment: .center) {
                    // Zeichen statt nur Farbe (WCAG 1.4.1): ✗ = offen, wie Android.
                    Image(systemName: "xmark.circle").foregroundStyle(.red).accessibilityHidden(true)
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
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(failed.isEmpty ? Brand.card(scheme) : Color.red.opacity(0.14), in: RoundedRectangle(cornerRadius: Brand.cardRadius))
        .padding(.horizontal, 16)
    }
}
