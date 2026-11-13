import Foundation
import Observation
import PhaseWakeCore

/// Lern-Übersicht am iPhone (Entscheid r1AD 13.11.2026; wie `LearnLogStore` auf Android): die Meldungen der Apple Watch,
/// warum die Lernphase eine Nacht nicht aufzeichnet (``LearnSkip``, Benutzerdaten), und die Nächte mit Wecker (bei jedem
/// Neuplanen der Sicherung gemerkt, ``LearnLog/mergeAlarmNights(_:planned:now:calendar:)``). Höchstens 14 Nächte je
/// Art; privat im von der Sicherung ausgenommenen Ordner (`PrivateFiles`), weder in der iCloud-Sicherung noch in
/// `.pwbak`. «Verlauf löschen» leert beides. Keine neuen Rechte.
@MainActor
@Observable
final class LearnLogStore {
    static let shared = LearnLogStore()

    /// Meldungen der Uhr, älteste zuerst.
    private(set) var skips: [LearnSkip]
    /// Nächte mit Wecker: Nachtdatum → Zielzeit.
    private(set) var alarmNights: [String: Date]
    /// Seit wann das iPhone Wecker merkt; davor ist «kein Wecker» unbekannt.
    private(set) var alarmsSince: Date?

    /// Gespeicherter Stand (Textformate aus ``LearnLog``, wie auf Android).
    private struct Stored: Codable {
        var skips: String
        var alarmNights: String
        var alarmsSince: Double?
    }

    @ObservationIgnored private let url = PrivateFiles.directory.appendingPathComponent("learn-log.v1")

    private init() {
        let stored = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(Stored.self, from: $0) }
        skips = LearnLog.decodeSkips(stored?.skips)
        alarmNights = LearnLog.decodeAlarmNights(stored?.alarmNights)
        alarmsSince = stored?.alarmsSince.map { Date(timeIntervalSince1970: $0) }
    }

    /// Ab diesem Nachtdatum gilt ein fehlender Wecker als «kein Wecker» (die Nacht nach dem ersten Merken); sonst `nil`.
    var alarmsKnownFrom: String? {
        alarmsSince.map { NightDate.adding(1, to: SleepNotes.nightDate($0, calendar: .gregorianLocal)) }
    }

    /// Meldung der Uhr (`SyncKeys.learnSkip`) speichern; dieselbe Nacht ersetzt die frühere.
    func receive(_ text: String) {
        guard let skip = LearnSkip.decode(text) else {
            DiagStore.shared.log(.error, "learn skip unreadable")
            return
        }
        skips = LearnLog.add(skip, to: skips, calendar: .gregorianLocal)
        save()
        DiagStore.shared.log(.sync, "learn skip \(skip.reason.rawValue)\(skip.battery.map { " \($0)%" } ?? "")")
    }

    /// Neuplanen um `now`: die Nächte mit Wecker der nächsten Tage aus `alarms` (mit `holidays`) merken; vergangene
    /// bleiben. Beim ersten Mal beginnt das Wissen über Wecker.
    func noteAlarms(_ alarms: [AlarmSpec], holidays: Set<String>, now: Date) {
        let calendar = Calendar.gregorianLocal
        let planned = LearnLog.upcomingTargets(alarms, now: now, calendar: calendar, holidays: holidays)
        let merged = LearnLog.mergeAlarmNights(alarmNights, planned: planned, now: now, calendar: calendar)
        guard merged != alarmNights || alarmsSince == nil else { return }
        alarmNights = merged
        if alarmsSince == nil { alarmsSince = now }
        save()
    }

    /// «Verlauf löschen»: Meldungen und vergangene Wecker-Nächte weg; das Wissen über Wecker beginnt ab `now` neu.
    func clear(now: Date) {
        skips = []
        alarmNights = alarmNights.filter { $0.value > now }
        alarmsSince = now
        save()
    }

    private func save() {
        let stored = Stored(skips: LearnLog.encodeSkips(skips), alarmNights: LearnLog.encodeAlarmNights(alarmNights),
                            alarmsSince: alarmsSince?.timeIntervalSince1970)
        if let data = try? JSONEncoder().encode(stored) { try? data.write(to: url, options: .atomic) }
    }
}
