import PhaseWakeCore
import SwiftUI

// MARK: - Stand an die Uhr und Weckart beim Klingeln

extension PhoneModel {
    /// Weckart zur Kennung aus «klingeln»: gespeicherter Wecker oder der laufende Probewecker/Powernap.
    func ringSpec(_ alarmId: String?) -> AlarmSpec? {
        alarms.first { $0.id == alarmId } ?? transient.spec(for: alarmId)
    }

    /// Wecker, Feiertage, Schlafziel und Lernphase an die Uhr; der Anwendungskontext ersetzt jeweils den ganzen Stand.
    /// Erst wenn Stand und Lernphase gelesen sind (nach dem ersten Entsperren): Ein Kontext aus dem leeren Stand gälte auf
    /// der Uhr als neuer und löschte Lernwerte, Schlafziel, Feiertage und den Schalter für das automatische Einschlafen.
    /// `catchUpState` sendet nach dem Lesen.
    func publishToWatch(_ snapshot: AlarmSnapshot? = nil) {
        guard stateLoaded, PhoneLearning.shared.load() else {
            DiagStore.shared.log(.sync, "context not sent: state not readable yet")
            return
        }
        let file = SettingsFile.phone
        PhoneConnectivity.shared.publish(snapshot ?? storage.snapshot(), holidays: holidays.list,
                                         sleepGoal: PhoneConnectivity.afterReset(sleepGoal, key: Self.sleepGoalKey, in: file),
                                         autoOnset: PhoneConnectivity.afterReset(WatchNight.autoOnset, key: BackupIO.autoOnsetKey, in: file),
                                         learning: PhoneLearning.shared.watchSnapshot())
    }
}
