import CoreMotion
import Foundation
import os
import PhaseWakeCore

extension LearnRecorder {
    /// Pause nach jedem Abschnitt beim Nachrechnen der Einschlafzeit (`WatchOnset`): Das Lesen von bis zu
    /// 4 Std. Werten am Beginn der Smart-Alarm-Sitzung belegt die Uhr sonst ohne Unterbruch. Mit 24 Abschnitten
    /// höchstens rund 5 s mehr, innerhalb der Rechenzeit (`WatchOnset.budget`); die Frist bis fail-closed bleibt.
    nonisolated static let onsetPause: TimeInterval = 0.2

    /// Bewegung je Epoche ab `start` aus dem Rekorder: nach den schon fertigen Epochen `done` bis `until`
    /// (Epochengrenze), Abschnitt für Abschnitt (``chunk``), bis `deadline`. Läuft abseits des Main-Threads (auch
    /// `WatchOnset`). `pause`: Ruhe nach jedem Abschnitt (``pauseLength(_:now:deadline:)``), damit die Rechnung die Uhr
    /// nicht am Stück belegt; die Lernphase (kurze Rechenzeit im Hintergrund) pausiert nicht. Jeder Abschnitt liest in
    /// einem eigenen `autoreleasepool`: Die Werte eines Abschnitts (10 Min., 30 000) werden gleich
    /// freigegeben, statt sich über 8–12 Std. bis zum Ende anzusammeln (Jetsam). Freier Speicher vorher und nachher
    /// (`os_proc_available_memory`, MB) als Diagnose-Zeile der Art `kind`.
    nonisolated static func recordedMotions(start: Date, done: [Double?], until stop: Date, deadline: Date,
                                            pause: TimeInterval = 0, kind: DiagKind = .measureEnd) -> [Double?] {
        let epochs = RecorderEpochs(start: start, motions: done)
        let recorder = CMSensorRecorder()
        var chunkStart = epochs.closedUntil
        let freeBefore = freeMegabytes()
        var chunks = 0
        defer {
            if chunks > 0 {
                DiagStore.shared.log(kind, "recorder read chunks=\(chunks) free=\(freeBefore)->\(freeMegabytes()) MB")
            }
        }
        while chunkStart < stop, Date() < deadline {
            let chunkEnd = min(chunkStart.addingTimeInterval(chunk), stop)
            autoreleasepool {
                if let list = recorder.accelerometerData(from: chunkStart, to: chunkEnd) {
                    var iterator = NSFastEnumerationIterator(list)
                    while let item = iterator.next() {
                        guard let data = item as? CMRecordedAccelerometerData else { continue }
                        let a = data.acceleration
                        epochs.add(at: data.startDate, x: a.x, y: a.y, z: a.z)
                    }
                }
            }
            chunks += 1
            epochs.close(until: chunkEnd)
            guard epochs.closedUntil > chunkStart else { break }
            chunkStart = epochs.closedUntil
            let rest = pauseLength(pause, now: Date(), deadline: deadline)
            if chunkStart < stop, rest > 0 { Thread.sleep(forTimeInterval: rest) }
        }
        return epochs.motions
    }

    /// Freier Speicher der App in MB (`os_proc_available_memory`; 0, wenn das System keinen Wert liefert, etwa im
    /// Simulator).
    nonisolated static func freeMegabytes() -> Int {
        os_proc_available_memory() / 1_048_576
    }

    /// Länge der Pause nach einem Abschnitt: `pause`, aber nie über `deadline` hinaus (nie negativ).
    nonisolated static func pauseLength(_ pause: TimeInterval, now: Date, deadline: Date) -> TimeInterval {
        max(0, min(pause, deadline.timeIntervalSince(now)))
    }
}
