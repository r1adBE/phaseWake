import PhaseWakeCore
import SwiftUI

// MARK: - Runde 2: Geräusche, Abendroutine, Wochenrückblick

extension PhoneModel {
    /// Ob für diese Nacht aufgenommen werden soll: eigene Wahl, sonst die Vorgabe.
    func soundRecEnabled(night: String) -> Bool {
        if let choice = soundRecChoice, choice.night == night { return choice.on }
        return soundRecDefault
    }

    /// Schalter auf der Startseite: merkt die Wahl für diese Nacht und startet bzw. stoppt die Aufnahme.
    func setSoundRec(_ on: Bool, night: String) async {
        setSoundRecChoice(night: night, on: on)
        if on {
            let started = await recorder.start(nextAlarmTarget: nextAlarmTarget())
            // Mikrofon verweigert: Schalter bleibt aus.
            if !started { setSoundRecChoice(night: night, on: false) }
        } else {
            recorder.stop()
        }
    }

    /// Startet die Aufnahme beim Öffnen der App von selbst (nur mit erteiltem Mikrofon; ein Stopp gilt für
    /// die ganze Nacht). Selbst für diese Nacht eingeschaltet: wieder starten, solange der Abend läuft
    /// (bis zum Weckfenster). Nur die Vorgabe «an»: erst ab der Bettzeit der kommenden Nacht bzw. ohne
    /// Wecker ab 21 Uhr (`SoundEvents.mayAutoStart`); davor nur, wenn der Nutzer selbst einschaltet.
    func autoStartSoundRec(now: Date = Date()) async {
        guard !recorder.running, recorder.permissionGranted else { return }
        let plan = nextPlan(now: now)
        let night = SleepNotes.nightDate(now, calendar: .gregorianLocal)
        if let choice = soundRecChoice, choice.night == night {
            guard choice.on, SleepNotes.entryVisible(now: now, nextWindowStart: plan?.windowStart, calendar: .gregorianLocal) else { return }
        } else {
            guard soundRecDefault,
                  SoundEvents.mayAutoStart(now: now, nextTarget: plan?.target, goalMinutes: sleepGoal, calendar: .gregorianLocal, windowStart: plan?.windowStart,
                                           latencyMinutes: learnedLatency) else { return }
        }
        await recorder.start(nextAlarmTarget: plan?.target)
    }

    func setSoundRecDefault(_ on: Bool) async {
        if on, !(await SoundRecorder.requestPermission()) { return }
        soundRecDefault = on
        SettingsFile.phone.set(on, forKey: Self.soundRecDefaultKey)
    }

    private func setSoundRecChoice(night: String, on: Bool) {
        soundRecChoice = (night: night, on: on)
        SettingsFile.phone.set("\(night)|\(on ? "1" : "0")", forKey: Self.soundRecNightKey)
    }

    /// Zielzeit des nächsten Weckers (früh geweckte Nächte übersprungen).
    private func nextAlarmTarget(now: Date = Date()) -> Date? { nextPlan(now: now)?.target }

    // MARK: Abendroutine

    var canAddRoutineItem: Bool { routineItems.count < Routine.maxItems }

    func addRoutineItem(_ text: String) {
        let clean = Routine.cleanText(text)
        guard canAddRoutineItem, !clean.isEmpty else { return }
        setRoutineItems(routineItems + [RoutineItem(id: Routine.customId(UUID().uuidString.lowercased()), text: clean)])
    }

    func removeRoutineItem(_ id: String) { setRoutineItems(routineItems.filter { $0.id != id }) }

    private func setRoutineItems(_ items: [RoutineItem]) {
        let text = Routine.encodeItems(items)
        privateNotes.setRoutineItemsText(text)
        routineItems = Routine.decodeItems(text)
    }

    func routineDone(night: String) -> [String] { routineTicks[night] ?? [] }

    func toggleRoutine(_ id: String, night: String) {
        routineTicks = Routine.toggle(routineTicks, date: night, id: id)
        privateNotes.setRoutineTicks(routineTicks)
    }

    /// Anzeigename: Vorschläge übersetzt, eigene mit ihrem Text.
    func routineName(_ item: RoutineItem) -> String {
        switch item.id {
        case Routine.phone: L("routine_item_phone")
        case Routine.light: L("routine_item_light")
        case Routine.water: L("routine_item_water")
        default: item.text
        }
    }

    // MARK: Wochenrückblick

    func setWeeklyReview(_ enabled: Bool) {
        weeklyReview = enabled
        SettingsFile.phone.set(enabled, forKey: Self.weeklyKey)
        Task {
            if enabled { await bedtime.requestPermission() }
            await replanWeekly()
        }
    }

    /// Plant die Mitteilung zum nächsten Sonntag 18 Uhr mit dem aktuellen Stand neu.
    func replanWeekly() async {
        guard stateLoaded || catchUpState() else { return }
        await weekly.reschedule(model: self)
    }
}
