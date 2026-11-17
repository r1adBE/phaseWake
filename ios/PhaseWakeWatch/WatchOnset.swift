import CoreMotion
import Foundation
import PhaseWakeCore

/// Einschlafzeit der Nacht auf der Apple Watch, nachgerechnet beim Beginn der Smart-Alarm-Sitzung aus dem
/// Bewegungs-Rekorder (``RecorderOnset``: dieselbe Regel wie die Einschlaf-Messung der Galaxy Watch, mit Grundrauschen
/// und 15 Min Ruhe). Gemessen ab «Gute Nacht» (Zeit des Tippens) bzw. ab 60 Min vor der Bettzeit (``ShortNight``),
/// frühestens ab dem Start des Rekorders. Der Rekorder läuft dafür jede Nacht, in der die App ab 60 Min vor der
/// Bettzeit lief (geöffnet, etwa über die Erinnerung am Abend, oder Hintergrund-Aktualisierung), solange «Einschlafen
/// automatisch erkennen» ein ist (`LearnRecorder+AutoOnset`), sonst nur nach «Gute Nacht», in der Lernphase und bei
/// «Nacht aufzeichnen». Ist der Schalter aus, rechnet die Uhr wie Android nur nach «Gute Nacht».
///
/// Ergebnis (``Result``): Ohne Rekorder in dieser Nacht (nicht gestartet, keine Erlaubnis) ist die Einschlafzeit
/// unbekannt, und die Untergrenze kurzer Nächte gilt nicht (`notMeasured`, wie Android ohne Messung); ebenso, wenn er
/// erst nach dem Beginn der Einschlafdauer im Hintergrund startete
/// (``RecorderOnset/lateStart(recorderFrom:bedAt:foreground:)``). Startete er spät bei offener App, gilt die Rechnung
/// ab seinem Start. Lief er, aber die Rechnung bringt keine Einschlafzeit (aufgegeben, Weckfenster begonnen, zu wenige
/// Werte, zu spät gerechnet), gilt die Untergrenze bis 10 Min vor der Zielzeit (`failed`, fail-closed, wie die Galaxy
/// Watch nach einer begonnenen Messung ohne Ergebnis). Puls ab 15 Min vor dem Beginn und Zeiten am Ladegerät siehe
/// `LearnRecorder+Charging`. Neue Werte liefert der Rekorder erst nach bis zu 3 Min; die letzten Minuten
/// vor der Sitzung fehlen darum. Die Einschlafzeit geht in die laufende Aufzeichnung (`LearnRecorder.noteOnset`), samt
/// Beginn mit der Nacht ans iPhone (`NightRecord.bedAt`, `asleepAt`) und
/// ins Diagnose-Protokoll, nach «Gute Nacht» samt Vergleich mit der Schätzung.
enum WatchOnset {
    /// Rechenzeit für das Lesen des Rekorders (bis 4 Std. Werte, rund 720 000).
    static let budget: TimeInterval = 25
    /// Spätestens so lange nach dem Beginn der Rechnung gilt sie als gescheitert (`SmartAlarm`, fail-closed).
    static let timeout: TimeInterval = budget + 15

    /// Ergebnis der Rechnung.
    enum Result: Equatable {
        /// Eingeschlafen um `at`, Beginn der Einschlafdauer `bedAt` (für die Nacht im Verlauf).
        case asleep(Date, bedAt: Date)
        /// Kein Rekorder in dieser Nacht (oder Schalter aus, Powernap, Probewecker): unbekannt, keine Untergrenze.
        case notMeasured
        /// Rekorder lief, aber keine Einschlafzeit: Untergrenze bis 10 Min vor der Zielzeit.
        case failed
    }

    /// Ergebnis und Grundrauschen der nachgerechneten Messung
    /// (``RecorderOnset/measure(motions:since:threshold:personalFloor:latest:extendCap:worn:)``: Epochen bis zum
    /// erkannten Einschlafen bzw. Aufgeben; `nil` ohne Rechnung oder wenn sie ins Weckfenster lief). Das Grundrauschen
    /// gilt im Weckfenster derselben Nacht als Vorwissen (``WakeDetector/useOnsetFloor(_:)``), wie auf der Galaxy Watch
    /// (`OnsetFloorStore`).
    typealias Outcome = (result: Result, floor: Double?)

    /// Rechnet die Einschlafzeit für die Nacht `plan` (Wecker `spec`, Schwellen `config`) nach.
    @MainActor
    static func compute(plan: WakePlan, spec: AlarmSpec?, config: DetectorConfig, goodNight: GoodNightState?,
                        sleepGoal: Int) async -> Outcome {
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
        guard let bedAt = recorderBed(label: label, recorderFrom: span.from, begin: begin, automatic: manual == nil,
                                      latest: latest, sleepMinutes: sleepMinutes) else {
            return (.notMeasured, nil)
        }
        let since = max(begin, span.from)
        let stop = [now.addingTimeInterval(-LearnRecorder.dataDelay), span.until,
                    since.addingTimeInterval(RecorderOnset.maxExtended),
                    latest.addingTimeInterval(-RecorderOnset.giveUpLead)].min() ?? now
        guard stop > since else {
            DiagStore.shared.log(.measureStart, "\(label) not computed: too late")
            return (.failed, nil)
        }
        let data = await readRecorder(since: since, stop: stop, deadline: now.addingTimeInterval(budget))
        let windowStart = AlarmSchedule.plan(spec, target: latest).windowStart
        // Grundrauschen nur bis zur Epoche, in der das Einschlafen erkannt ist bzw. bis zum Aufgeben; lief die Messung
        // ins Weckfenster, keines (wie `OnsetMonitor` auf der Galaxy Watch).
        let extendCap = RecorderOnset.extendCap(since: since, windowStart: windowStart)
        let (result, floor) = RecorderOnset.measure(motions: data.motions, since: since,
                                                    threshold: config.lightThreshold,
                                                    personalFloor: config.personalFloor, latest: latest,
                                                    extendCap: extendCap,
                                                    worn: RecorderOnset.Worn(heartRates: data.rates,
                                                                             heartLead: SleepOnset.wornEpochs,
                                                                             away: data.away))
        let empty = data.motions.filter { $0 == nil }.count
        let pulsed = data.rates.compactMap { $0 }.count
        let thr = NoiseFloor.format(NoiseFloor.light(threshold: config.lightThreshold, floor: floor))
        let stat = "\(label) recorder from \(Format.time(since)) epochs=\(data.motions.count) "
            + "empty=\(empty) hr=\(pulsed) "
            + "charging=\(data.away.filter { $0 }.count) "
            + "floor=\(NoiseFloor.format(floor)) thr=\(thr)"
        return report(result, floor: floor, stat: stat, bedAt: bedAt, manual: manual)
    }

    /// Beginn der Einschlafdauer, ab dem gemessen wird; `nil`, wenn der Rekorder (ab `recorderFrom`) erst danach im
    /// Hintergrund startete (dann nicht gemessen). Rekorder erst nach dem Beginn der Einschlafdauer gestartet (wie die
    /// Galaxy Watch): bei offener App ab seinem Start gemessen (die Person war wach), im Hintergrund nicht gemessen.
    @MainActor
    private static func recorderBed(label: String, recorderFrom: Date, begin: Date, automatic: Bool, latest: Date,
                                    sleepMinutes: Int) -> Date? {
        let plannedBed = RecorderOnset.bedAt(since: begin, automatic: automatic, latest: latest,
                                             sleepMinutes: sleepMinutes)
        let late = RecorderOnset.lateStart(recorderFrom: recorderFrom, bedAt: plannedBed,
                                           foreground: LearnRecorder.shared.recorderStartedActive)
        let after = "\(label) recorder from \(Format.logTime(recorderFrom)), "
            + "after bedtime \(Format.logTime(plannedBed))"
        switch late {
        case .background:
            DiagStore.shared.log(.measureEnd, "\(after): onset unknown, not measured")
            return nil
        case .foreground:
            DiagStore.shared.log(.measureStart, "\(after), app open: from recorder start")
        case .onTime:
            break
        }
        return late == .foreground ? recorderFrom : plannedBed
    }

    /// Bewegung (ab `since` bis `stop`, Rechenzeit bis `deadline`), Puls ab ``SleepOnset/wornEpochs`` davor und
    /// Epochen am Ladegerät aus dem Rekorder lesen.
    @MainActor
    private static func readRecorder(since: Date, stop: Date,
                                     deadline: Date) async -> (motions: [Double?], rates: [Double?], away: [Bool]) {
        // Niedrige Priorität mit Pausen zwischen den Abschnitten: begrenzte Rechenlast, 40-s-Grenze bleibt.
        let motions = await Task.detached(priority: .utility) {
            LearnRecorder.recordedMotions(start: since, done: [], until: stop, deadline: deadline,
                                          pause: LearnRecorder.onsetPause, kind: .measureStart)
        }.value
        // Puls aus Apple Health dazu: Eine ruhige Strecke zählt nur, wenn die Uhr davor und darin getragen wurde;
        // ein Puls bis ``SleepOnset/wornEpochs`` vor dem Beginn zählt für die erste (sonst zählte einer kurz vor «Gute
        // Nacht» nie).
        let lead = SleepOnset.wornEpochs
        let pulseStart = since.addingTimeInterval(-TimeInterval(lead * DetectorConfig.epochSeconds))
        let pulses = await LearnRecorder.shared.heartRates(from: pulseStart, to: stop)
        let rates = RecorderEpochs.heartRates(pulses, start: pulseStart, count: motions.count + lead)
        // Zeiten am Ladegerät (abgetastet): leer, und sie verschieben die Zeitgrenze.
        LearnRecorder.shared.noteCharging()
        let away = RecorderOnset.awayEpochs(LearnRecorder.shared.chargeSpans(until: stop), start: since,
                                            count: motions.count)
        return (motions, rates, away)
    }

    /// Ergebnis `result` der nachgerechneten Messung ins Protokoll (`stat`) und, wenn eingeschlafen, in die
    /// Aufzeichnung; nach «Gute Nacht» (`manual`) samt Vergleich mit der Schätzung.
    @MainActor
    private static func report(_ result: RecorderOnset.Result, floor: Double?, stat: String, bedAt: Date,
                               manual: GoodNightState?) -> Outcome {
        switch result {
        case .asleep(let found, _):
            // Automatisch nie vor der Bettzeit eingeschlafen; nach «Gute Nacht» unverändert.
            let at = RecorderOnset.onsetAt(found: found, bedAt: bedAt)
            let still = at != found ? " (still since \(Format.logTime(found)))" : ""
            DiagStore.shared.log(.phase, "\(stat), asleep \(Format.logTime(at))" + still)
            if let manual {
                let diff = Int((at.timeIntervalSince(manual.asleep) / 60).rounded())
                let sign = diff >= 0 ? "+" : ""
                DiagStore.shared.log(
                    .phase,
                    "onset estimate \(Format.logTime(manual.asleep)), "
                        + "recorder \(Format.logTime(at)) (\(sign)\(diff) min)"
                )
            }
            LearnRecorder.shared.noteOnset(bedAt: bedAt, asleepAt: at)
            return (.asleep(at, bedAt: bedAt), floor)
        case .gaveUp(_, let reason):
            DiagStore.shared.log(.measureEnd, "\(stat), \(reason)")
        case .open:
            DiagStore.shared.log(.measureEnd, "\(stat), window began")
        }
        return (.failed, floor)
    }
}
