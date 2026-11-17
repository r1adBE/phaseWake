import os

/// Ob die App gerade selbst eine Erlaubnis des Systems erfragt (Mitteilungen, Health, Mikrofon, Wecker). Die Abfrage
/// macht die App «inaktiv»; die Abdeckung für das Vorschaubild (iPhone) bleibt dann weg, damit hinter der Abfrage der
/// erklärende Text sichtbar bleibt. Zähler statt Schalter, weil zwei Abfragen überlappen können; von jedem Thread.
enum PermissionPrompt {
    private static let count = OSAllocatedUnfairLock(initialState: 0)

    /// Vor der Abfrage aufrufen, danach immer ``end()`` (mit `defer`).
    static func begin() { count.withLock { $0 += 1 } }

    static func end() { count.withLock { $0 = max(0, $0 - 1) } }

    /// Läuft gerade mindestens eine eigene Abfrage?
    static var isAsking: Bool { count.withLock { $0 > 0 } }
}
