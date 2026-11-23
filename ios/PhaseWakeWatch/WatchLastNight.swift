import Foundation
import Observation
import PhaseWakeCore

/// Letzte Nacht des Verlaufs für die Startseite der Uhr: gemerkt und beobachtet, statt bei
/// jedem Neuzeichnen den ganzen Verlauf zu lesen und zu zerlegen. Alle Änderungen der Nächte auf der Uhr laufen über
/// ``add(_:)``, ``setFeeling(_:alarmId:windowStart:)``, ``replace(_:)`` und ``clear()``; so zeigt die Startseite eine
/// neue Nacht sofort.
@MainActor
@Observable
final class WatchLastNight {
    @ObservationIgnored private let storage: PhaseWakeStorage
    /// Jüngste gespeicherte Nacht (wie `storage.nights().last`); `nil` = keine oder Verlauf (noch) nicht lesbar.
    private(set) var night: NightRecord?
    /// Ob der Verlauf schon gelesen wurde; vor dem ersten Entsperren nicht (dann holt ``loadIfNeeded()`` es nach).
    @ObservationIgnored private var loaded = false

    init(storage: PhaseWakeStorage) {
        self.storage = storage
        reload()
    }

    /// Liest den Verlauf, falls das bisher nicht ging (vor dem ersten Entsperren); sonst nichts.
    func loadIfNeeded() {
        if !loaded { reload() }
    }

    /// Fügt eine Nacht hinzu (``PhaseWakeStorage/add(_:now:)``). - Returns: ob sie gespeichert ist.
    @discardableResult
    func add(_ record: NightRecord) -> Bool {
        let saved = storage.add(record)
        reload()
        return saved
    }

    /// Speichert das Befinden der Nacht `alarmId`/`windowStart` (Wahl nach dem Wecken, ``WatchFeeling``;
    /// ``PhaseWakeStorage/setFeeling(_:alarmId:windowStart:)``). - Returns: die Nacht mit diesem Befinden, so wie sie
    /// jetzt gespeichert ist; `nil`, wenn es sie nicht gibt oder der Verlauf nicht lesbar ist.
    func setFeeling(_ feeling: Int, alarmId: String, windowStart: Date) -> NightRecord? {
        storage.setFeeling(feeling, alarmId: alarmId, windowStart: windowStart)
        reload()
        let night = stored(alarmId: alarmId, windowStart: windowStart)
        return night?.feeling == feeling ? night : nil
    }

    /// Gespeicherte Nacht `alarmId`/`windowStart` aus dem Verlauf; `nil`, wenn es sie nicht gibt oder er nicht lesbar
    /// ist (vor dem ersten Entsperren).
    func stored(alarmId: String, windowStart: Date) -> NightRecord? {
        storage.nights().first { $0.alarmId == alarmId && $0.windowStart == windowStart }
    }

    /// Ersetzt alle Nächte (``PhaseWakeStorage/replaceNights(_:now:)``).
    func replace(_ nights: [NightRecord]) {
        storage.replaceNights(nights)
        reload()
    }

    /// Löscht alle Nächte (``PhaseWakeStorage/clearNights()``).
    func clear() {
        storage.clearNights()
        reload()
    }

    private func reload() {
        loaded = storage.nightsReadable
        night = storage.nights().last
    }
}
