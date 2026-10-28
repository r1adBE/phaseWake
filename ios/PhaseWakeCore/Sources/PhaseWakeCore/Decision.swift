/// Warum geweckt wurde. Wird im Verlauf gespeichert; die Rohwerte entsprechen den Kotlin-Namen.
public enum WakeReason: String, CaseIterable, Sendable {
    /// Deutliche Bewegung in mehreren Epochen: Die Person ist schon wach.
    case awake = "AWAKE"
    /// Leichte Bewegung, und der Puls ist (falls gemessen) gestiegen.
    case lightSleep = "LIGHT_SLEEP"
    /// Zielzeit erreicht, ohne dass vorher ein guter Moment kam.
    case deadline = "DEADLINE"
}

/// Ergebnis des ``WakeDetector`` für eine Epoche.
public enum Decision: Equatable, Sendable {
    /// Noch kein guter Moment: weiter messen.
    case wait
    /// Jetzt wecken.
    case wake(WakeReason)
}
