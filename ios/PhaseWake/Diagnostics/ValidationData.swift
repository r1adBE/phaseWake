import Foundation
import PhaseWakeCore

/// Daten des Validierungs-Modus (``Validation``) für Diagnose-Bericht und Export: eigene
/// Nächte, abgeschlossene Aufzeichnungen der Uhr, gelernte Werte und die Schlafphasen aus Apple Health (nur gelesen,
/// nur im Speicher; ohne Zugriff keine, dann hat keine Nacht Referenz). Rechnet nur, ändert nichts am Wecken. Wie
/// `ValidationData` (Android).
@MainActor
enum ValidationData {
    /// Alle Entscheide mit Referenzphase; leer ohne Nächte und Aufzeichnungen.
    static func entries(model: PhoneModel) async -> [Validation.Entry] {
        let recordings = PhoneLearning.shared.recordings.filter(\.finished).map(\.learnNight)
        // Wie die übrigen Kennzahlen über die jüngsten 90 Nächte, nicht den ganzen Verlauf.
        let nights = NightHistory.latest(model.nights)
        if nights.isEmpty && recordings.isEmpty { return [] }
        let health = await model.health.sessions(days: NightHistory.insightNights)
        return Validation.evaluate(nights: nights, recordings: recordings, sessions: health.sessions,
                                   clearedAt: model.historyClearedAt,
                                   learned: PhoneLearning.shared.state.values, calendar: .gregorianLocal)
    }
}
