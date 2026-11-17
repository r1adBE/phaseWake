import PhaseWakeCore
import SwiftUI

// MARK: - Geräusch-Aufnahme in der Nacht

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
            guard choice.on,
                  SleepNotes.entryVisible(now: now, nextWindowStart: plan?.windowStart,
                                          calendar: .gregorianLocal) else { return }
        } else {
            guard soundRecDefault,
                  SoundEvents.mayAutoStart(now: now, nextTarget: plan?.target, goalMinutes: sleepGoal,
                                           calendar: .gregorianLocal, windowStart: plan?.windowStart,
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
}
