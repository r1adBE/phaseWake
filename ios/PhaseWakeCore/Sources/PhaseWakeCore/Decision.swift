/// Warum geweckt wurde. Wird im Verlauf gespeichert; die Rohwerte entsprechen den Kotlin-Namen.
/// `exact` und `shortNight` kennen ältere Versionen nicht; der ``Codec`` schreibt sie darum zusätzlich als `deadline`.
public enum WakeReason: String, CaseIterable, Sendable {
    /// Deutliche Bewegung in mehreren Epochen: Die Person ist schon wach.
    case awake = "AWAKE"
    /// Leichte Bewegung, und der gemessene Puls ist über den Basispuls gestiegen.
    case lightSleep = "LIGHT_SLEEP"
    /// Zielzeit erreicht, ohne dass vorher ein guter Moment kam.
    case deadline = "DEADLINE"
    /// Wecker «Genau» (``AlarmSpec/isExact``): zur Zielzeit geweckt, nie früher; die Uhr hat nur für den Verlauf gemessen.
    case exact = "EXACT"
    /// Kurze Nacht (``ShortNight/blocksEarly(onset:now:sleepGoalMinutes:)``): frühe Momente übergangen, zur Zielzeit geweckt.
    case shortNight = "SHORT_NIGHT"

    /// Ob zur Zielzeit geweckt wurde, weil kein früher Moment zählte (`deadline`, `exact`, `shortNight`).
    public var atTarget: Bool { self == .deadline || self == .exact || self == .shortNight }

    /// Ob in dieser Nacht früheres Wecken erlaubt war (nicht «Genau», keine kurze Nacht).
    public var earlyAllowed: Bool { self != .exact && self != .shortNight }
}

/// Ergebnis des ``WakeDetector`` für eine Epoche.
public enum Decision: Equatable, Sendable {
    /// Noch kein guter Moment: weiter messen.
    case wait
    /// Jetzt wecken.
    case wake(WakeReason)
}
