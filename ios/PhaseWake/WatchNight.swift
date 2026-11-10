import Foundation
import Observation
import PhaseWakeCore

/// Was die Apple Watch ausser den Weckern zur Nacht meldet (wie `RecordSync` und `BackupOnset` auf Android):
/// - «Nacht aufzeichnen»: Start und Stopp gehen als Befehl an die Uhr, die misst; sie meldet den Beginn der laufenden
///   Aufzeichnung im Anwendungskontext zurück («Aufzeichnung läuft seit …» auf der Startseite).
/// - «Gute Nacht»: frühere Zielzeit einer Nacht (``OnsetShift``). Das iPhone merkt sie sich (eine neuere ersetzt eine
///   ältere, eine ohne frühere Zielzeit hebt sie auf), stellt den Zweitwecker dieser Nacht darauf und zeigt sie als
///   nächsten Plan; nach der Nacht fällt sie weg.
@MainActor
@Observable
final class WatchNight {
    static let shared = WatchNight()

    /// Beginn der laufenden Aufzeichnung auf der Uhr; `nil` = keine oder unbekannt.
    private(set) var recordSince: Date?
    /// Befehl an die Uhr gesendet, ihre Antwort steht aus (Knopf kurz gesperrt).
    private(set) var recordPending = false

    private static let shiftKey = "watchOnsetShift"
    /// So lange wartet der Knopf höchstens auf die Antwort der Uhr.
    private static let pendingLimit: Duration = .seconds(15)

    private init() {}

    // MARK: «Nacht aufzeichnen»

    /// Ob die Uhr «Nacht aufzeichnen» kennt (gekoppelt, Uhr-App ab ``WatchProtocol/recording``); nur dann zeigt die
    /// Startseite die Karte (wie auf Android nur mit einer Uhr, die es kennt).
    func watchCanRecord(_ model: PhoneModel) -> Bool {
        BackupAlarm.watchPresent && (model.watchProtocol ?? 0) >= WatchProtocol.recording
    }

    /// Stand der Uhr (`SyncKeys.recordSince`); 0 bzw. `nil` = keine Aufzeichnung.
    func noteRecording(since: Date?) {
        recordSince = since
        recordPending = false
    }

    /// Knopf auf der Startseite: Start bzw. Stopp an die Uhr (sofort oder nachgereicht; ein Start gilt dort nur frisch).
    func setRecording(_ on: Bool) {
        recordPending = true
        DiagStore.shared.log(.sync, "record \(on ? "start" : "stop") sent")
        PhoneConnectivity.shared.send(command: on ? SyncKeys.recordStart : SyncKeys.recordStop)
        Task {
            try? await Task.sleep(for: Self.pendingLimit)
            self.recordPending = false
        }
    }

    // MARK: Einschlafen automatisch erkennen

    /// Schalter «Einschlafen automatisch erkennen» (Standard ein, wie Android; in der Sicherung wie bisher): Die Apple
    /// Watch startet dann jede Nacht den Bewegungs-Rekorder für die Einschlafzeit (`AppleAutoOnset`). Von jedem Thread.
    nonisolated static var autoOnset: Bool {
        SettingsFile.phone.object(forKey: BackupIO.autoOnsetKey) as? Bool ?? true
    }

    /// Ändert den Schalter (Einstellungen) und schickt ihn an die Uhr.
    func setAutoOnset(_ on: Bool) {
        SettingsFile.phone.set(on, forKey: BackupIO.autoOnsetKey)
        autoOnsetRevision += 1
        DiagStore.shared.log(.sync, "auto onset \(on ? "on" : "off")")
        PhoneModel.shared.publishToWatch()
    }

    /// Zähler, damit die Einstellungen nach `setAutoOnset` neu zeichnen.
    private(set) var autoOnsetRevision = 0

    // MARK: «Gute Nacht»

    /// Gemerkte frühere Zielzeit, solange ihre Nacht läuft (danach gelöscht); sonst `nil`.
    func shift(now: Date = Date()) -> OnsetShift? {
        guard let shift = OnsetShift.decode(SettingsFile.phone.string(forKey: Self.shiftKey)) else { return nil }
        guard shift.expired(now: now, grace: BackupAlarm.grace) else { return shift }
        SettingsFile.phone.removeObject(forKey: Self.shiftKey)
        return nil
    }

    /// `plan` mit der früheren Zielzeit, falls «Gute Nacht» diese Nacht vorverlegt hat (``OnsetShift/applyTo(_:now:grace:)``).
    func apply(_ plan: WakePlan?, now: Date = Date()) -> WakePlan? {
        guard let plan, let shift = shift(now: now) else { return plan }
        return shift.applyTo(plan, now: now, grace: BackupAlarm.grace)
    }

    /// Eingestellte Zielzeit der Nacht, die die Uhr zur früheren `target` gemeldet hat (für das Aussetzen des
    /// Zweitweckers, wenn die Uhr geweckt hat); sonst `nil`.
    func original(alarmId: String?, target: Date?) -> Date? {
        guard let shift = shift(), let target, shift.alarmId == alarmId, shift.target == OnsetShift.millis(target) else { return nil }
        return Date(timeIntervalSince1970: TimeInterval(shift.original) / 1000)
    }

    /// Meldung der Uhr (`SyncKeys.onsetShift`): merken und den Zweitwecker neu planen. Eine vorbeigegangene Nacht, eine
    /// ältere Meldung als die gemerkte oder dieselbe noch einmal ändert nichts.
    func received(_ shift: OnsetShift) async {
        guard !shift.expired(now: Date(), grace: BackupAlarm.grace) else { return }
        if let current = OnsetShift.decode(SettingsFile.phone.string(forKey: Self.shiftKey)), shift == current || shift.at < current.at {
            return
        }
        SettingsFile.phone.set(shift.encode(), forKey: Self.shiftKey)
        let target = Date(timeIntervalSince1970: TimeInterval(shift.target) / 1000)
        DiagStore.shared.log(.scheduled, "watch onset target \(Format.time(target))")
        await PhoneModel.shared.backup.reschedule(PhoneModel.shared.alarms)
    }
}
