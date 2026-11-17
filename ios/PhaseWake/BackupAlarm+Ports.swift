import Foundation
import PhaseWakeCore

/// Was der Zweitwecker vom System braucht (AlarmKit ab iOS 26, sonst Mitteilungen), hinter einem Protokoll:
/// In der App ``SystemBackupPlatform``, in Tests ein Ersatz, der nur festhält. Die Entscheidungen (was gestellt,
/// behalten, verworfen, gestoppt wird) bleiben in ``BackupAlarm``.
@MainActor
protocol BackupPlatform: AnyObject {
    /// Ob AlarmKit da ist (ab iOS 26); sonst gibt es nur Mitteilungen.
    var alarmKit: Bool { get }
    /// Stellt einen iPhone-Alarm (``BackupAlarm/schedule(id:at:fromWatch:snoozesLeft:tone:chain:)``).
    func place(id: UUID, at date: Date, fromWatch: Bool, snoozesLeft: Int, tone: WakeTone?,
               chain: Int) async -> BackupAlarm.Placement
    /// Stellt die Kette als Mitteilung (``BackupAlarm/scheduleNotification(id:at:tone:fixed:count:skip:)``).
    /// - Returns: wie viele der Kette danach stehen.
    func notify(id: UUID, at date: Date, tone: WakeTone?, fixed: Bool, count: Int, skip: Set<String>) async -> Int
    /// Verwirft einen Alarm: AlarmKit (ein Fehler bei einem noch stehenden Alarm ins Protokoll) und die wartende Kette.
    func cancel(_ id: UUID)
    /// Verwirft einen geplanten AlarmKit-Alarm ohne Protokoll (Mitklingeln, noch nicht klingelnd).
    func cancelScheduled(_ id: UUID)
    /// Stoppt einen AlarmKit-Alarm (Fehler ignoriert).
    func stop(_ id: UUID)
    /// Kennungen aller AlarmKit-Alarme; scheitert das Lesen zweimal (``ReadRetry``), keine.
    func alarmKitIds() async -> Set<UUID>
    /// Kennungen der geplanten, noch nicht klingelnden AlarmKit-Alarme; `nil` = nicht lesbar oder kein AlarmKit.
    func scheduledAlarmKitIds() -> [UUID]?
    /// Kennungen der gerade klingelnden AlarmKit-Alarme.
    func alertingIds() -> [UUID]
    /// Wartende Mitteilungen: Kennung und ob sie zu Kalenderzeit steht (`UNCalendarNotificationTrigger`).
    func pendingNotifications() async -> [(id: String, calendar: Bool)]
    /// Kennungen der zugestellten Mitteilungen.
    func deliveredNotificationIds() async -> [String]
    /// Entfernt diese Mitteilungen, wartend und zugestellt.
    func removeNotifications(_ ids: [String])
}

/// Was der Zweitwecker von der übrigen App braucht: in der App ``AppBackupHost``, in Tests ein Ersatz.
@MainActor
protocol BackupHost: AnyObject {
    /// Ob der gespeicherte Stand gelesen ist (`PhoneModel.stateLoaded`).
    var stateLoaded: Bool { get }
    /// Liest den Stand nach, falls er inzwischen lesbar ist (`PhoneModel.catchUpState`). - Returns: ob gelesen.
    func catchUpState() -> Bool
    /// Wecker der App.
    var alarms: [AlarmSpec] { get }
    /// Feiertage, an denen Wecker mit «An Feiertagen nicht wecken» schweigen.
    var holidayDates: Set<String> { get }
    /// Einmaligen Wecker nach dem Klingeln ausschalten (`PhoneModel.disableAfterRing`).
    func disableAfterRing(_ spec: AlarmSpec)
    /// Der iPhone-Alarm `id` hat geklingelt (Probewecker, `PhoneTransient.phoneAlerted`).
    func phoneAlerted(_ id: String)
    /// Nacht mit der früheren Zielzeit aus «Gute Nacht» der Uhr (`WatchNight.apply`).
    func shifted(_ plan: WakePlan?, now: Date) -> WakePlan?
    /// Eingestellte Zielzeit einer von «Gute Nacht» vorverlegten Nacht (`WatchNight.original`), sonst `nil`.
    func originalTarget(alarmId: String?, target: Date?) -> Date?
    /// Nächte mit Wecker für die Lern-Übersicht (`LearnLogStore.noteAlarms`).
    func noteAlarms(_ alarms: [AlarmSpec], now: Date)
    /// «stop» an die Uhr für diese Nacht.
    func sendStop(_ night: RingNight)
}

/// Die App als ``BackupHost``: `PhoneModel`, `WatchNight`, `LearnLogStore`, `PhoneConnectivity`.
@MainActor
final class AppBackupHost: BackupHost {
    var stateLoaded: Bool { PhoneModel.shared.stateLoaded }
    func catchUpState() -> Bool { PhoneModel.shared.catchUpState() }
    var alarms: [AlarmSpec] { PhoneModel.shared.alarms }
    var holidayDates: Set<String> { PhoneModel.shared.holidays.dates }
    func disableAfterRing(_ spec: AlarmSpec) { PhoneModel.shared.disableAfterRing(spec) }
    func phoneAlerted(_ id: String) { PhoneModel.shared.transient.phoneAlerted(id) }
    func shifted(_ plan: WakePlan?, now: Date) -> WakePlan? { WatchNight.shared.apply(plan, now: now) }
    func originalTarget(alarmId: String?, target: Date?) -> Date? {
        WatchNight.shared.original(alarmId: alarmId, target: target)
    }
    func noteAlarms(_ alarms: [AlarmSpec], now: Date) {
        LearnLogStore.shared.noteAlarms(alarms, holidays: PhoneModel.shared.holidays.dates, now: now)
    }
    func sendStop(_ night: RingNight) { PhoneConnectivity.shared.send(command: SyncKeys.stop, night: night) }
}
