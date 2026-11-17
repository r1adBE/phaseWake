import Foundation
import PhaseWakeCore

/// Auswerten einer Aufzeichnung: Ende und Grund, Bewegung aus dem Rekorder, Puls dazu, Versand ans iPhone.
extension LearnRecorder {
    /// Ende der Auswertung: von Hand gestoppt dann (höchstens Ende des Rekorders); sonst bei «Nacht aufzeichnen» das
    /// Ende des Rekorders (12 Std.), in der Lernphase das Ende des Klingelns (höchstens Ende des Rekorders), ohne
    /// Klingeln die Zielzeit.
    nonisolated static func end(of mark: Mark) -> Date {
        if let stopped = mark.stoppedAt { return min(stopped, mark.until) }
        if mark.manual == true { return mark.until }
        return AppleLearnRecord.end(wokeAt: mark.wokeAt, target: mark.target, until: mark.until)
    }

    /// Grund des Endes: von Hand bzw. Akku wie gemerkt, sonst nach 12 Std. (von Hand) bzw. beim Wecken (Lernphase).
    nonisolated static func reason(of mark: Mark) -> RecordEnd {
        if let code = mark.stopReason { return RecordEnd.of(code) }
        return mark.manual == true ? .cap : .wake
    }

    /// Letzte Epochengrenze ab `from` bis `until`.
    nonisolated private static func boundary(from: Date, until: Date) -> Date {
        let epochs = max(0, Int(until.timeIntervalSince(from)) / DetectorConfig.epochSeconds)
        let kept = min(epochs, NightRecording.maxEpochs)
        return from.addingTimeInterval(TimeInterval(kept * DetectorConfig.epochSeconds))
    }

    /// Wertet `pending` bis zu den abrufbaren Werten aus (höchstens `budget` Sekunden), speichert den Stand und schickt
    /// die Aufzeichnung ans iPhone, sobald sie bis zum Ende ausgewertet ist (oder die Werte bald verfallen).
    func process(_ pending: Mark, budget: TimeInterval) async {
        let now = Date()
        let end = Self.end(of: pending)
        let stop = Self.boundary(from: pending.from, until: min(end, now.addingTimeInterval(-Self.dataDelay)))
        let deadline = now.addingTimeInterval(budget)
        let motions = await Task.detached(priority: .utility) {
            Self.motions(pending, until: stop, deadline: deadline)
        }.value
        // Inzwischen verworfen (Lernphase aus, Verlauf gelöscht): nichts speichern.
        guard mark == pending else { return }
        var updated = pending
        updated.motions = motions
        let span = Int(Self.boundary(from: pending.from, until: end).timeIntervalSince(pending.from))
        let complete = motions.count * DetectorConfig.epochSeconds >= span
        if complete || now.timeIntervalSince(end) > Self.giveUpAfter {
            await finish(updated)
        } else {
            save(updated)
        }
    }

    /// Bewegung je Epoche aus dem Rekorder ab dem gespeicherten Stand bis `until` (Epochengrenze), Abschnitt für
    /// Abschnitt, bis `deadline`. Läuft abseits des Main-Threads.
    nonisolated private static func motions(_ mark: Mark, until stop: Date, deadline: Date) -> [Double?] {
        recordedMotions(start: mark.from, done: mark.motions, until: stop, deadline: deadline)
    }

    /// Puls aus Apple Health dazu, Aufzeichnung ans iPhone (mit der Einschlafzeit, falls bekannt), Stand löschen.
    func finish(_ done: Mark) async {
        let end = Self.end(of: done)
        let pulses = await heartRates(from: done.from, to: end)
        guard mark?.from == done.from else { return }
        let current = mark ?? done
        let rates = RecorderEpochs.heartRates(pulses, start: done.from, count: done.motions.count)
        let reason = Self.reason(of: current)
        var recording = NightRecording(start: done.from,
                                       epochs: RecorderEpochs.epochs(motions: done.motions, heartRates: rates),
                                       end: end, endReason: reason)
        if let bed = current.bedAt, let asleep = current.asleepAt {
            recording = recording.with(bedAt: bed, asleepAt: asleep)
        }
        let measured = done.motions.compactMap { $0 }.count
        let withPulse = rates.compactMap { $0 }.count
        DiagStore.shared.log(
            .measureEnd,
            "record end \(Self.reasonText(reason, battery: nil)) epochs=\(done.motions.count) "
                + "motion=\(measured) hr=\(withPulse)"
        )
        WatchConnectivityBridge.shared.send(learnRecording: NightRecording.encode(recording))
        save(nil)
    }
}
