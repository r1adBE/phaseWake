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

    @ObservationIgnored private let url: URL
    /// Ob die Datei gelesen ist (oder fehlt); vor dem ersten Entsperren nicht: dann nie schreiben (Prüfrunde 2 SP-1).
    @ObservationIgnored private(set) var loaded = false
    /// Meldungen der Uhr, die vor dem Lesen kamen; ``load()`` nimmt sie danach auf.
    @ObservationIgnored private var pending: [String] = []

    /// `directory`: Ordner der Datei; Tests geben einen eigenen, die App nutzt ``shared``.
    init(directory: URL = PrivateFiles.directory) {
        url = directory.appendingPathComponent("learn-log.v1")
        skips = []
        alarmNights = [:]
        alarmsSince = nil
        load()
    }

    /// Liest die Datei, falls noch nicht geschehen («fehlt» = leer, «nicht lesbar» = später, ``ProtectedFile``), und
    /// nimmt danach gemerkte Meldungen auf. - Returns: ob gelesen (schon vorher oder jetzt).
    @discardableResult
    func load() -> Bool {
        if !loaded {
            let file = ProtectedFile.read(url)
            guard file != .unreadable else { return false }
            if case let .data(data) = file, let stored = try? JSONDecoder().decode(Stored.self, from: data) {
                skips = LearnLog.decodeSkips(stored.skips)
                alarmNights = LearnLog.decodeAlarmNights(stored.alarmNights)
                alarmsSince = stored.alarmsSince.map { Date(timeIntervalSince1970: $0) }
            }
            loaded = true
        }
        let queued = pending
        pending = []
        queued.forEach(receive)
        return true
    }

    /// Ab diesem Nachtdatum gilt ein fehlender Wecker als «kein Wecker» (die Nacht nach dem ersten Merken); sonst `nil`.
    var alarmsKnownFrom: String? {
        alarmsSince.map { NightDate.adding(1, to: SleepNotes.nightDate($0, calendar: .gregorianLocal)) }
    }

    /// Meldung der Uhr (`SyncKeys.learnSkip`) speichern; dieselbe Nacht ersetzt die frühere.
    func receive(_ text: String) {
        guard load() else {
            pending.append(text)
            return
        }
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
        guard load() else { return }
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
        guard load() else { return }
        skips = []
        alarmNights = alarmNights.filter { $0.value > now }
        alarmsSince = now
        save()
    }

    private func save() {
        guard loaded else { return }
        let stored = Stored(skips: LearnLog.encodeSkips(skips), alarmNights: LearnLog.encodeAlarmNights(alarmNights),
                            alarmsSince: alarmsSince?.timeIntervalSince1970)
        if let data = try? JSONEncoder().encode(stored) { try? data.write(to: url, options: .atomic) }
    }
}
