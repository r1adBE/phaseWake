import BackgroundTasks
import Foundation

/// Hintergrund-Aktualisierung des Zweitweckers: iOS weckt die App ab und zu kurz (`BGAppRefreshTask`), dann reicht die
/// Sicherung wieder `BackupAlarm.horizon` weit – auch wenn die App wochenlang nicht geöffnet wird. Dabei verfallen auch
/// Geräusch-Ausschnitte nach 7 Tagen (`SoundRecorder.expire`). Wann und ob iOS das
/// tut, entscheidet das System; der Nacht-Check warnt, wenn die Sicherung trotzdem knapp wird.
@MainActor
enum BackupRefresh {
    /// Kennung der Aufgabe; steht auch in `Info.plist` unter `BGTaskSchedulerPermittedIdentifiers`.
    nonisolated static let identifier = "com.phasewake.app.backupRefresh"
    /// Frühestens so lange nach dem letzten Planen.
    private static let interval: TimeInterval = 12 * 3600

    /// Meldet die nächste Aktualisierung an; ersetzt eine schon angemeldete. Scheitert sie (Simulator, im System
    /// ausgeschaltet), bleibt das Neuplanen beim Öffnen der App.
    static func submit() {
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date().addingTimeInterval(interval)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            DiagStore.shared.log(.error, "background refresh not submitted")
        }
    }

    /// Läuft im Hintergrund: nächste Aktualisierung anmelden, die Sicherung neu planen und abgelaufene Geräusch-Ausschnitte
    /// löschen (7 Tage, auch ohne Öffnen der App; Audit D2).
    static func run() async {
        submit()
        DiagStore.shared.log(.scheduled, "background refresh")
        let model = PhoneModel.shared
        await model.backup.reschedule(model.alarms)
        model.recorder.expire()
    }
}
