import Observation
import PhaseWakeCore

/// Letzte Nacht des Verlaufs für die Startseite der Uhr (Audit 17.11. PERF-04): gemerkt und beobachtet, statt bei
/// jedem Neuzeichnen den ganzen Verlauf zu lesen und zu zerlegen. Alle Änderungen der Nächte auf der Uhr laufen über
/// ``add(_:)``, ``replace(_:)`` und ``clear()``; so zeigt die Startseite eine neue Nacht sofort.
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

    /// Fügt eine Nacht hinzu (``PhaseWakeStorage/add(_:)``). - Returns: ob sie gespeichert ist.
    @discardableResult
    func add(_ record: NightRecord) -> Bool {
        let saved = storage.add(record)
        reload()
        return saved
    }

    /// Ersetzt alle Nächte (``PhaseWakeStorage/replaceNights(_:)``).
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
