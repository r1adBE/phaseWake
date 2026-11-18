import Foundation
import PhaseWakeCore

// MARK: - Älter als 90 Tage löschen

extension PhoneModel {
    /// «Älter als 90 Tage löschen» (`HistoryPrune`, wie Android): eigene Nächte, Notizen, Befinden, Haken und
    /// Aufzeichnungen, die bis vor 90 Tagen endeten, weg; Nächte aus Apple Health bis dahin ausgeblendet (wie
    /// ``clearHistory()`` über den Zeitpunkt «Verlauf löschen», nie früher als ein späteres Löschen). Die Uhr behält
    /// ihre Nächte; schickt sie eine davon noch einmal, nimmt das iPhone sie nicht an (`HistoryClear.keeps`).
    /// Geräusche verfallen ohnehin nach 7 Tagen; die Lernphase läuft weiter, ihre Aufzeichnungen bleiben
    /// (``HistoryPrune/keepsRecording(start:end:cutoff:learningSince:)``).
    func clearOlderHistory(now: Date = Date()) {
        let cutoff = HistoryPrune.cutoff(now: now)
        let cleared = HistoryPrune.clearedAt(current: historyClearedAt, cutoff: cutoff)
        historyClearedAt = cleared
        SettingsFile.phone.set(NSNumber(value: TimeBounds.millis(cleared)), forKey: Self.historyClearedKey)
        if let kept = storage.clearNights(before: cutoff) { nights = kept }
        let left = privateNotes.clearNotes(before: cutoff, calendar: .gregorianLocal)
        notes = left.notes
        healthFeelings = left.feelings
        routineTicks = HistoryPrune.dates(routineTicks, cutoff: cutoff, calendar: .gregorianLocal)
        privateNotes.setRoutineTicks(routineTicks)
        PhoneLearning.shared.dropRecordings(endedBy: cutoff)
        // Geteilte CSV-Dateien in den temporären Dateien gleich mit.
        NightsExport.cleanUp()
        DiagStore.shared.log(.sync, "history cleared older than \(HistoryPrune.days) days")
    }
}
