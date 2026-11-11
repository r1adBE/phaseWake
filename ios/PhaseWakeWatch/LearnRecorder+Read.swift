import CoreMotion
import Foundation
import PhaseWakeCore

extension LearnRecorder {
    /// Pause nach jedem Abschnitt beim Nachrechnen der Einschlafzeit (`WatchOnset`, Vollaudit AP2-3): Das Lesen von bis zu
    /// 4 Std. Werten am Beginn der Smart-Alarm-Sitzung belegt die Uhr sonst ohne Unterbruch. Mit 24 Abschnitten höchstens
    /// rund 5 s mehr, innerhalb der Rechenzeit (`WatchOnset.budget`); die Frist bis fail-closed bleibt.
    nonisolated static let onsetPause: TimeInterval = 0.2

    /// Bewegung je Epoche ab `start` aus dem Rekorder: nach den schon fertigen Epochen `done` bis `until`
    /// (Epochengrenze), Abschnitt für Abschnitt (``chunk``), bis `deadline`. Läuft abseits des Main-Threads (auch
    /// `WatchOnset`). `pause`: Ruhe nach jedem Abschnitt (``pauseLength(_:now:deadline:)``), damit die Rechnung die Uhr
    /// nicht am Stück belegt; die Lernphase (kurze Rechenzeit im Hintergrund) pausiert nicht.
    nonisolated static func recordedMotions(start: Date, done: [Double?], until stop: Date, deadline: Date,
                                            pause: TimeInterval = 0) -> [Double?] {
        let epochs = RecorderEpochs(start: start, motions: done)
        let recorder = CMSensorRecorder()
        var chunkStart = epochs.closedUntil
        while chunkStart < stop, Date() < deadline {
            let chunkEnd = min(chunkStart.addingTimeInterval(chunk), stop)
            if let list = recorder.accelerometerData(from: chunkStart, to: chunkEnd) {
                var iterator = NSFastEnumerationIterator(list)
                while let item = iterator.next() {
                    guard let data = item as? CMRecordedAccelerometerData else { continue }
                    epochs.add(at: data.startDate, x: data.acceleration.x, y: data.acceleration.y, z: data.acceleration.z)
                }
            }
            epochs.close(until: chunkEnd)
            guard epochs.closedUntil > chunkStart else { break }
            chunkStart = epochs.closedUntil
            let rest = pauseLength(pause, now: Date(), deadline: deadline)
            if chunkStart < stop, rest > 0 { Thread.sleep(forTimeInterval: rest) }
        }
        return epochs.motions
    }

    /// Länge der Pause nach einem Abschnitt: `pause`, aber nie über `deadline` hinaus (nie negativ).
    nonisolated static func pauseLength(_ pause: TimeInterval, now: Date, deadline: Date) -> TimeInterval {
        max(0, min(pause, deadline.timeIntervalSince(now)))
    }
}
