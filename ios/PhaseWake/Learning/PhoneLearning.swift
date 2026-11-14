import Foundation
import Observation
import PhaseWakeCore

/// Lernphase am iPhone (`docs/PHASENERKENNUNG.md`, Abschnitt 10; wie `LearningRepository` und `updateLearning` auf
/// Android): Stand und Zustimmung (``LearningState``), die Aufzeichnungen der Apple Watch (``NightRecording``, die
/// letzten 14, aus der Lernphase und von «Nacht aufzeichnen»; Verlauf, Export und Schlafwert lesen sie hier), Zählen
/// der Nächte und Lernen der Werte nach 7 gezählten Nächten. Der Puls kommt auf der Apple Watch aus
/// Apple Health, darum gilt dort ``Learning/minHeartRateEpochsApple``; die Einschlafdauer trägt das iPhone aus Apple Health
/// nach («Im Bett» bis zum ersten Schlaf, nur Daten von Apple; ``HealthSleep/onsetSamples(from:to:)``), sobald sie dort
/// liegt.
///
/// Alles privat im von der Sicherung ausgenommenen Ordner (`PrivateFiles`), weder in der iCloud-Sicherung noch in
/// `.pwbak` – wie die Aufzeichnungen, auf denen es beruht. Stand und gelernte Werte gehen im Anwendungskontext an die Uhr
/// (`PhoneModel.publishToWatch`).
@MainActor
@Observable
final class PhoneLearning {
    static let shared = PhoneLearning()

    /// Stand und Zustimmung.
    private(set) var state: LearningState
    /// Aufzeichnungen der Uhr, älteste zuerst.
    private(set) var recordings: [NightRecording]

    @ObservationIgnored private let stateURL = PrivateFiles.directory.appendingPathComponent("learning.v1")
    @ObservationIgnored private let recordingsURL = PrivateFiles.directory.appendingPathComponent("learn-recordings.v1")
    /// Nachtragen der Einschlafdauer läuft (nicht doppelt).
    @ObservationIgnored private var filling = false

    private init() {
        state = (try? Data(contentsOf: stateURL)).flatMap { try? JSONDecoder().decode(LearningState.self, from: $0) } ?? LearningState()
        let texts = (try? Data(contentsOf: recordingsURL)).flatMap { try? JSONDecoder().decode([String].self, from: $0) } ?? []
        recordings = texts.compactMap(NightRecording.decode).sorted { $0.start < $1.start }
    }

    /// Ob die Uhr aufzeichnen kann: Apple Watch gekoppelt, Phase Wake darauf in einer Version mit Lernphase. Nur dann
    /// fragt die Startseite und zeigt den Fortschritt (wie «Uhr kann aufzeichnen» auf Android).
    func watchCanLearn(_ model: PhoneModel) -> Bool {
        BackupAlarm.watchPresent && (model.watchProtocol ?? 0) >= WatchProtocol.learning
    }

    /// Gezählte Nächte seit dem Beginn der Lernphase.
    var countedNights: Int {
        guard let since = state.since else { return 0 }
        return Learning.counted(recordings.map(\.learnNight), since: since, calendar: .gregorianLocal).count
    }

    /// «Nacht N von 7», solange sie läuft; sonst `nil`.
    var progress: Int? {
        state.active ? Learning.progress(countedNights) : nil
    }

    // MARK: Aufzeichnungen

    /// Aufzeichnung der Uhr (`SyncKeys.learnRecording`) speichern, Einschlafdauer nachtragen, zählen.
    func receive(_ text: String) async {
        guard let recording = NightRecording.decode(text) else {
            DiagStore.shared.log(.error, "learn recording unreadable")
            return
        }
        DiagStore.shared.log(.sync, "recording received epochs=\(recording.epochs.count) end=\(recording.endReason?.rawValue ?? "-")")
        // Endete vor «Verlauf löschen» (spät zugestellt): nicht zurückholen (Audit D-02/AP-6, wie Android).
        guard HistoryClear.keeps(end: recording.until, clearedAt: PhoneModel.shared.historyClearedAt) else {
            DiagStore.shared.log(.suppressed, "recording before history clear dropped")
            return
        }
        var list = recordings.filter { $0.start != recording.start }
        list.append(recording)
        setRecordings(list)
        await fillOnsets()
        update(received: recording)
    }

    /// Beim Öffnen und nach dem Start: Einschlafdauer nachtragen (Apple Health legt den Schlaf erst nach dem Aufwachen
    /// ab), zählen und, wenn fertig, lernen.
    func refresh() async {
        await fillOnsets()
        update(received: nil)
    }

    /// Trägt bei Aufzeichnungen ab dem Beginn der Lernphase Beginn und Einschlafzeit aus Apple Health nach, sobald dort
    /// beides liegt. Apple Health geht vor; die Einschlafzeit, die die Uhr aus dem Rekorder nachgerechnet hat
    /// (`WatchOnset`), gilt nur ohne Daten in Apple Health.
    private func fillOnsets() async {
        guard !filling, let since = state.since, state.active else { return }
        filling = true
        defer { filling = false }
        var changed = false
        var list = recordings
        for (index, recording) in list.enumerated() where recording.start >= since {
            let samples = await PhoneModel.shared.health.onsetSamples(from: recording.start, to: recording.until)
            // In Millisekunden verglichen wie im Dateiformat: sonst gälte jede gelesene Aufzeichnung als geändert.
            guard let onset = Learning.healthOnset(start: recording.start, inBed: samples.inBed, asleep: samples.asleep),
                  Self.ms(onset.bedAt) != recording.bedAt.map(Self.ms) || Self.ms(onset.asleepAt) != recording.asleepAt.map(Self.ms)
            else { continue }
            list[index] = recording.with(bedAt: onset.bedAt, asleepAt: onset.asleepAt)
            changed = true
        }
        // Inzwischen gelöscht («Verlauf löschen»): nichts zurückschreiben.
        if changed, recordings.map(\.start) == list.map(\.start) { setRecordings(list) }
    }

    /// Zählt die Aufzeichnungen seit dem Beginn (``Learning/counted(_:since:calendar:)``); nach 7 gezählten Nächten werden die Werte gelernt,
    /// gespeichert und an die Uhr geschickt. `received`: eben angekommene Aufzeichnung, fürs Diagnose-Protokoll
    /// («learn night counted 3/7» bzw. «… not counted epochs=210»).
    private func update(received: NightRecording?) {
        guard let since = state.since, state.active else { return }
        let counted = Learning.counted(recordings.map(\.learnNight), since: since, calendar: .gregorianLocal)
        if let received, received.start >= since {
            let night = received.learnNight
            let result = Learning.counts(night) ? "counted" : "not counted epochs=\(Learning.measuredEpochs(night))"
            DiagStore.shared.log(.sync, "learn night \(result) \(counted.count)/\(Learning.nights)")
        }
        guard Learning.finished(counted.count) else { return }
        let values = Learning.learn(Array(counted.suffix(Learning.nights)), minHeartRateEpochs: Learning.minHeartRateEpochsApple)
        var next = state
        guard next.finish(values, now: Date()) else { return }
        setState(next)
        DiagStore.shared.log(.sync, "learn done \(state.snapshot.diagLine) lat=\(values.latencyMinutes.map { "\($0)" } ?? "-")")
    }

    private static func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    // MARK: Zustimmung, «Neu lernen», «Verlauf löschen»

    /// Zustimmung (Einrichtung, Karte auf der Startseite, Schalter in den Einstellungen): an beginnt sie bzw. läuft
    /// weiter, aus zeichnet die Uhr nicht mehr automatisch auf (eine laufende Aufzeichnung der Lernphase endet).
    func setConsent(_ on: Bool) {
        var next = state
        let started = next.setConsent(on, now: Date())
        setState(next)
        DiagStore.shared.log(.sync, "learn consent \(on ? "on" : "off")\(started ? " started" : "")")
        update(received: nil)
    }

    /// «Neu lernen»: ab jetzt von vorn; die bisherigen Werte gelten auf iPhone und Uhr weiter, bis die neuen Nächte
    /// gezählt sind.
    func relearn() {
        var next = state
        next.relearn(now: Date())
        setState(next)
        DiagStore.shared.log(.sync, "learn relearn \(state.snapshot.diagLine)")
    }

    /// «Verlauf löschen»: Aufzeichnungen weg, Lernphase ab jetzt von vorn, gelernte Werte verworfen (auch auf der Uhr),
    /// Lern-Übersicht leer.
    func restart() {
        setRecordings([])
        LearnLogStore.shared.clear(now: Date())
        var next = state
        next.restart(now: Date())
        setState(next)
        DiagStore.shared.log(.sync, "learn restarted")
    }

    /// Karte «Angepasst an dich» bestätigt.
    func setSeen() {
        var next = state
        next.setSeen()
        setState(next)
    }

    // MARK: Speichern

    /// Stand für die Uhr (`PhoneModel.publishToWatch`); ohne Zeitpunkt (iPhone-App neu installiert) mit «jetzt» und
    /// gespeichert (Fix S-3, ``LearningState/stampIfNever(now:)``).
    func watchSnapshot() -> LearningSnapshot {
        var next = state
        if next.stampIfNever(now: Date()) {
            state = next
            if let data = try? JSONEncoder().encode(next) { try? data.write(to: stateURL, options: .atomic) }
        }
        return state.snapshot
    }

    /// Speichert `next` und schickt den Stand an die Uhr, wenn er sich für sie geändert hat.
    private func setState(_ next: LearningState) {
        let sendToWatch = next.snapshot != state.snapshot
        let latencyChanged = next.values?.latencyMinutes != state.values?.latencyMinutes
        state = next
        if let data = try? JSONEncoder().encode(next) { try? data.write(to: stateURL, options: .atomic) }
        if sendToWatch { PhoneModel.shared.publishToWatch() }
        // Gelernte Einschlafdauer geändert: Die Bettzeit-Erinnerung folgt der einen Bettzeit (Audit O-03).
        if latencyChanged { Task { await PhoneModel.shared.replanBedtime() } }
    }

    /// Speichert die Aufzeichnungen (die letzten ``NightRecording/maxKept``, älteste zuerst).
    private func setRecordings(_ list: [NightRecording]) {
        recordings = Array(list.sorted { $0.start < $1.start }.suffix(NightRecording.maxKept))
        if recordings.isEmpty {
            try? FileManager.default.removeItem(at: recordingsURL)
        } else if let data = try? JSONEncoder().encode(recordings.map(NightRecording.encode)) {
            try? data.write(to: recordingsURL, options: .atomic)
        }
    }
}
