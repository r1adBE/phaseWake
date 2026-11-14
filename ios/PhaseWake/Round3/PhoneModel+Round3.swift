import PhaseWakeCore
import SwiftUI
import UIKit

// MARK: - Runde 3: Feiertage, Probewecker, Powernap, Start

extension PhoneModel {
    /// Weckart zur Kennung aus «klingeln»: gespeicherter Wecker oder der laufende Probewecker/Powernap.
    func ringSpec(_ alarmId: String?) -> AlarmSpec? {
        alarms.first { $0.id == alarmId } ?? transient.spec(for: alarmId)
    }

    /// Feiertage geändert (Wahl, neuer Tag): merken, an die Uhr, Sicherung und Erinnerung neu planen.
    func holidaysChanged() {
        storage.holidays = holidays.list
        publishToWatch()
        Task { await backup.reschedule(alarms) }
        Task { await replanBedtime() }
    }

    /// Wecker, Feiertage, Schlafziel und Lernphase an die Uhr; der Anwendungskontext ersetzt jeweils den ganzen Stand.
    /// Erst wenn Stand und Lernphase gelesen sind (nach dem ersten Entsperren): Ein Kontext aus dem leeren Stand gälte auf
    /// der Uhr als neuer und löschte Lernwerte, Schlafziel, Feiertage und den Schalter für das automatische Einschlafen
    /// (Prüfrunde 2 HG-1/SP-1). `catchUpState` sendet nach dem Lesen.
    func publishToWatch(_ snapshot: AlarmSnapshot? = nil) {
        guard stateLoaded, PhoneLearning.shared.load() else {
            DiagStore.shared.log(.sync, "context not sent: state not readable yet")
            return
        }
        PhoneConnectivity.shared.publish(snapshot ?? storage.snapshot(), holidays: holidays.list, sleepGoal: sleepGoal,
                                         learning: PhoneLearning.shared.watchSnapshot())
    }

    /// «Nächstes Mal überspringen» bzw. rückgängig; geht an die Uhr wie jede Änderung. Nach früh geweckter Nacht
    /// (Zielzeit noch vorn) trifft es den nächsten Termin, nicht den erledigten heutigen.
    func setSkipped(_ alarm: AlarmSpec, _ skipped: Bool) {
        let updated = skipped
            ? AlarmSchedule.skipNext(alarm, now: Date(), doneUntil: doneUntil[alarm.id], calendar: .gregorianLocal, holidays: holidays.dates)
            : AlarmSchedule.undoSkip(alarm)
        upsert(updated)
    }

    /// Beim Start: Ereignis fürs Protokoll, Feiertage laden, dann alles planen.
    func startUp() async {
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        DiagStore.shared.log(.boot, "app start")
        if device.batteryLevel >= 0 { DiagStore.shared.log(.battery, "\(Int((device.batteryLevel * 100).rounded())) %") }
        // Vor dem ersten Entsperren gestartet (etwa «Stopp» am Zweitwecker nach einem Neustart): nichts planen oder
        // verwerfen, bis der Stand lesbar ist; `catchUpState` holt dann alles nach (Tiefenaudit AP3-1).
        guard stateLoaded else {
            // Inzwischen lesbar: `catchUpState` liest und plant alles.
            if !catchUpState() { DiagStore.shared.log(.boot, "settings not readable yet, planning after unlock") }
            return
        }
        await planAll()
    }

    /// Stand nachlesen, falls er beim Start nicht lesbar war (vor dem ersten Entsperren), und dann alles planen wie beim
    /// Start (AP3-1). Aufgerufen beim ersten Entsperren, beim Öffnen und von jedem Neuplanen, das den Stand braucht.
    /// - Returns: ob der Stand gelesen ist (schon vorher oder jetzt).
    @discardableResult
    func catchUpState() -> Bool {
        if stateLoaded {
            // Lernphase erst jetzt lesbar: ihren Stand nachsenden.
            if catchUpFiles() { publishToWatch() }
            return true
        }
        guard SettingsFile.phone.isReadable else { return false }
        loadState()
        guard stateLoaded else { return false }
        // Feiertagswahl: beim Start galt das Land des Geräts (Prüfrunde 2 SP-2).
        let holidaysMoved = holidays.reloadChoice()
        holidays.adopt(lastSent: storage.holidays)
        DiagStore.shared.log(.boot, "settings readable, state loaded")
        // Wecker der Uhr, die vor dem Lesen kamen, jetzt anwenden.
        applyPendingRemote()
        catchUpFiles()
        // Bestehende Installation: keine Ersteinrichtung (wie beim Start in `PhaseWakeApp`).
        if !alarms.isEmpty || !nights.isEmpty { UserDefaults.standard.set(true, forKey: OnboardingView.doneKey) }
        // Vorher nicht gesendet (`publishToWatch`): jetzt der gelesene Stand.
        publishToWatch()
        Task { await planAll(holidaysMoved: holidaysMoved) }
        return true
    }

    /// Lernphase und Lern-Übersicht nachlesen und gemerkte Nächte speichern, sobald ihre Dateien lesbar sind (Prüfrunde 2
    /// HG-1/SP-1). - Returns: ob die Lernphase eben erst gelesen wurde.
    @discardableResult
    private func catchUpFiles() -> Bool {
        LearnLogStore.shared.load()
        let learning = PhoneLearning.shared, wasLoaded = learning.loaded
        applyPendingNights()
        return learning.load() && !wasLoaded
    }

    /// Feiertage laden, dann Sicherung, Erinnerungen, laufende Probewecker und Lernphase planen. `holidaysMoved`: Die
    /// Feiertage haben sich schon vorher geändert (`HolidayStore/reloadChoice()`), also auch merken und senden.
    private func planAll(holidaysMoved: Bool = false) async {
        if await holidays.load() || holidaysMoved {
            holidaysChanged()
        } else {
            await backup.reschedule(alarms)
            await replanBedtime()
        }
        await replanWeekly()
        await transient.restore()
        // Lernphase: Einschlafdauer nachtragen, zählen (fertig: Stand an die Uhr).
        await PhoneLearning.shared.refresh()
    }

    /// Beim Öffnen: neuer Tag → Feiertage neu auflösen (plant dabei alles neu); sonst die Sicherung nachziehen, damit
    /// sie wieder `BackupAlarm.horizon` weit reicht (der Abgleich lässt Unverändertes stehen).
    func becameActive() {
        // Stand vor dem ersten Entsperren gelesen (Prozess lief seither weiter): jetzt nachlesen (AP3-1).
        catchUpState()
        // Nachholen, falls es beim Start nicht ging (Merker vor dem ersten Entsperren nicht lesbar).
        RestoreJournal.recover(model: self)
        if holidays.refresh() {
            holidaysChanged()
        } else {
            Task { await backup.reschedule(alarms) }
        }
        BackupRefresh.submit()
        Task { await PhoneLearning.shared.refresh() }
    }
}
