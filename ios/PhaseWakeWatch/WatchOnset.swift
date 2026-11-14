import CoreMotion
import Foundation
import PhaseWakeCore

/// Einschlafzeit der Nacht auf der Apple Watch, nachgerechnet beim Beginn der Smart-Alarm-Sitzung aus dem
/// Bewegungs-Rekorder (``RecorderOnset``: dieselbe Regel wie die Einschlaf-Messung der Galaxy Watch, mit Grundrauschen
/// und 15 Min Ruhe). Gemessen ab «Gute Nacht» (Zeit des Tippens) bzw. ab 60 Min vor der Bettzeit (``ShortNight``), frühestens
/// ab dem Start des Rekorders. Der Rekorder läuft dafür jede Nacht, in der die App ab 60 Min vor der Bettzeit lief
/// (geöffnet, etwa über die Erinnerung am Abend, oder Hintergrund-Aktualisierung), solange «Einschlafen automatisch
/// erkennen» ein ist (`LearnRecorder+AutoOnset`), sonst nur nach «Gute Nacht», in der Lernphase und bei «Nacht
/// aufzeichnen». Ist der Schalter aus, rechnet die Uhr wie Android nur nach «Gute Nacht».
///
/// Ergebnis (``Result``): Ohne Rekorder in dieser Nacht (nicht gestartet, keine Erlaubnis) ist die Einschlafzeit
/// unbekannt, und die Untergrenze kurzer Nächte gilt nicht (`notMeasured`, wie Android ohne Messung); ebenso, wenn er erst
/// nach dem Beginn der Einschlafdauer im Hintergrund startete (``RecorderOnset/lateStart(recorderFrom:bedAt:foreground:)``,
/// Entscheid r1AD 14.11.2026 zu HG-2). Startete er spät bei offener App, gilt die Rechnung ab seinem Start. Lief er, aber
/// die Rechnung bringt keine Einschlafzeit (aufgegeben, Weckfenster begonnen, zu wenige Werte, zu spät gerechnet), gilt die
/// Untergrenze bis 10 Min vor der Zielzeit (`failed`, fail-closed, Entscheid r1AD 10.11.2026, wie die Galaxy Watch nach
/// einer begonnenen Messung ohne Ergebnis). Puls ab 15 Min vor dem Beginn und Zeiten am Ladegerät siehe F-1
/// (`LearnRecorder+Charging`). Neue Werte liefert der Rekorder erst nach bis zu 3 Min; die letzten Minuten
/// vor der Sitzung fehlen darum. Die Einschlafzeit geht in die laufende Aufzeichnung (`LearnRecorder.noteOnset`) und
/// ins Diagnose-Protokoll, nach «Gute Nacht» samt Vergleich mit der Schätzung.
enum WatchOnset {
    /// Rechenzeit für das Lesen des Rekorders (bis 4 Std. Werte, rund 720 000).
    static let budget: TimeInterval = 25
    /// Spätestens so lange nach dem Beginn der Rechnung gilt sie als gescheitert (`SmartAlarm`, fail-closed).
    static let timeout: TimeInterval = budget + 15

    /// Ergebnis der Rechnung.
    enum Result: Equatable {
        /// Eingeschlafen um `at`.
        case asleep(Date)
        /// Kein Rekorder in dieser Nacht (oder Schalter aus, Powernap, Probewecker): unbekannt, keine Untergrenze.
        case notMeasured
        /// Rekorder lief, aber keine Einschlafzeit: Untergrenze bis 10 Min vor der Zielzeit.
        case failed
    }

    /// Ergebnis und Grundrauschen der nachgerechneten Messung (``RecorderOnset/measure(motions:since:threshold:personalFloor:latest:extendCap:heartRates:)``:
    /// Epochen bis zum erkannten Einschlafen bzw. Aufgeben; `nil` ohne Rechnung oder wenn sie ins Weckfenster lief).
    /// Das Grundrauschen gilt im Weckfenster derselben Nacht als Vorwissen
    /// (``WakeDetector/useOnsetFloor(_:)``, Vollaudit K-1), wie auf der Galaxy Watch (`OnsetFloorStore`).
    typealias Outcome = (result: Result, floor: Double?)

    /// Rechnet die Einschlafzeit für die Nacht `plan` (Wecker `spec`, Schwellen `config`) nach.
    @MainActor
    static func compute(plan: WakePlan, spec: AlarmSpec?, config: DetectorConfig, goodNight: GoodNightState?, sleepGoal: Int) async -> Outcome {
        guard let spec, Transient.kindOf(plan.alarmId) == nil else { return (.notMeasured, nil) }
        let manual = goodNight.flatMap { $0.alarmId == plan.alarmId && $0.target == plan.target ? $0 : nil }
        let label = manual != nil ? "onset" : "auto onset"
        let latest = manual?.latest ?? plan.target
        let sleepMinutes = spec.sleepDurationMinutes ?? sleepGoal
        let begin = manual?.since ?? ShortNight.autoOnsetStart(target: latest, sleepGoalMinutes: sleepGoal,
                                                                sleepDurationMinutes: spec.sleepDurationMinutes)
        let now = Date()
        // Ohne «Gute Nacht» nur mit «Einschlafen automatisch erkennen» (wie Android).
        guard manual != nil || LearnRecorder.shared.autoOnset else {
            DiagStore.shared.log(.measureStart, "\(label) not computed: off")
            return (.notMeasured, nil)
        }
        guard CMSensorRecorder.authorizationStatus() == .authorized else {
            DiagStore.shared.log(.measureStart, "\(label) not computed: motion not allowed")
            return (.notMeasured, nil)
        }
        guard let span = LearnRecorder.shared.recorderSpan, span.until > begin, span.from < now else {
            DiagStore.shared.log(.measureStart, "\(label) not computed: no recorder")
            return (.notMeasured, nil)
        }
        // Rekorder erst nach dem Beginn der Einschlafdauer gestartet (Tiefenaudit AP3-2, Entscheid r1AD 14.11.2026 zu HG-2,
        // wie die Galaxy Watch): bei offener App ab seinem Start gemessen (die Person war wach), im Hintergrund nicht gemessen.
        let plannedBed = RecorderOnset.bedAt(since: begin, automatic: manual == nil, latest: latest, sleepMinutes: sleepMinutes)
        let late = RecorderOnset.lateStart(recorderFrom: span.from, bedAt: plannedBed, foreground: LearnRecorder.shared.recorderStartedActive)
        let after = "\(label) recorder from \(Format.logTime(span.from)), after bedtime \(Format.logTime(plannedBed))"
        switch late {
        case .background:
            DiagStore.shared.log(.measureEnd, "\(after): onset unknown, not measured")
            return (.notMeasured, nil)
        case .foreground:
            DiagStore.shared.log(.measureStart, "\(after), app open: from recorder start")
        case .onTime:
            break
        }
        let bedAt = late == .foreground ? span.from : plannedBed
        let since = max(begin, span.from)
        let stop = [now.addingTimeInterval(-LearnRecorder.dataDelay), span.until,
                    since.addingTimeInterval(RecorderOnset.maxExtended), latest.addingTimeInterval(-RecorderOnset.giveUpLead)].min() ?? now
        guard stop > since else {
            DiagStore.shared.log(.measureStart, "\(label) not computed: too late")
            return (.failed, nil)
        }
        let deadline = now.addingTimeInterval(budget)
        // Niedrige Priorität mit Pausen zwischen den Abschnitten (AP2-3): begrenzte Rechenlast, 40-s-Grenze bleibt.
        let motions = await Task.detached(priority: .utility) {
            LearnRecorder.recordedMotions(start: since, done: [], until: stop, deadline: deadline, pause: LearnRecorder.onsetPause)
        }.value
        // Puls aus Apple Health dazu (E-5, F-1): Eine ruhige Strecke zählt nur, wenn die Uhr davor und darin getragen wurde;
        // ein Puls bis ``SleepOnset/wornEpochs`` vor dem Beginn zählt für die erste (sonst zählte einer kurz vor «Gute Nacht» nie).
        let lead = SleepOnset.wornEpochs
        let pulseStart = since.addingTimeInterval(-TimeInterval(lead * DetectorConfig.epochSeconds))
        let pulses = await LearnRecorder.shared.heartRates(from: pulseStart, to: stop)
        let rates = RecorderEpochs.heartRates(pulses, start: pulseStart, count: motions.count + lead)
        // Zeiten am Ladegerät (abgetastet, F-1): leer, und sie verschieben die Zeitgrenze (HG-3).
        LearnRecorder.shared.noteCharging()
        let away = RecorderOnset.awayEpochs(LearnRecorder.shared.chargeSpans(until: stop), start: since, count: motions.count)
        let windowStart = AlarmSchedule.plan(spec, target: latest).windowStart
        // Grundrauschen nur bis zur Epoche, in der das Einschlafen erkannt ist bzw. bis zum Aufgeben; lief die Messung ins
        // Weckfenster, keines (Fix KN-3, wie `OnsetMonitor` auf der Galaxy Watch).
        let (result, floor) = RecorderOnset.measure(motions: motions, since: since, threshold: config.lightThreshold,
                                                    personalFloor: config.personalFloor, latest: latest,
                                                    extendCap: RecorderOnset.extendCap(since: since, windowStart: windowStart),
                                                    heartRates: rates, heartLead: lead, away: away)
        let stat = "\(label) recorder from \(Format.time(since)) epochs=\(motions.count) empty=\(motions.filter { $0 == nil }.count) hr=\(rates.compactMap { $0 }.count) "
            + "charging=\(away.filter { $0 }.count) "
            + "floor=\(NoiseFloor.format(floor)) thr=\(NoiseFloor.format(NoiseFloor.light(threshold: config.lightThreshold, floor: floor)))"
        switch result {
        case .asleep(let found, _):
            // Automatisch nie vor der Bettzeit eingeschlafen (Vollaudit K-4); nach «Gute Nacht» unverändert.
            let at = RecorderOnset.onsetAt(found: found, bedAt: bedAt)
            DiagStore.shared.log(.phase, "\(stat), asleep \(Format.logTime(at))" + (at != found ? " (still since \(Format.logTime(found)))" : ""))
            if let manual {
                let diff = Int((at.timeIntervalSince(manual.asleep) / 60).rounded())
                DiagStore.shared.log(.phase, "onset estimate \(Format.logTime(manual.asleep)), recorder \(Format.logTime(at)) (\(diff >= 0 ? "+" : "")\(diff) min)")
            }
            LearnRecorder.shared.noteOnset(bedAt: bedAt, asleepAt: at)
            return (.asleep(at), floor)
        case .gaveUp(_, let reason):
            DiagStore.shared.log(.measureEnd, "\(stat), \(reason)")
        case .open:
            DiagStore.shared.log(.measureEnd, "\(stat), window began")
        }
        return (.failed, floor)
    }
}
