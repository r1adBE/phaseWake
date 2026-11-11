import Foundation

/// Stand der Lernphase am iPhone (wie `LearningState` und `LearningRepository` auf Android): seit wann sie läuft
/// (`since`, `nil` = nie begonnen), die gelernten Werte (`values`, `nil` = noch nicht fertig) und ob die Karte
/// «Angepasst an dich» schon bestätigt ist (`seen`). `relearnPending`: «Neu lernen» läuft – die bisherigen `values`
/// gelten weiter, bis die neuen Nächte gezählt sind. `updatedAtMs`: letzte Änderung (ms), für den Abgleich mit der Uhr.
/// `consent`: Zustimmung (Audit F-02, Schalter «Lernphase»): `true` = an, `false` = aus bzw. «Später», `nil` = noch
/// nicht gefragt. Sie beginnt nie von selbst; «Verlauf löschen» (``restart(now:)``) und «Neu lernen»
/// (``relearn(now:)``) lassen die Zustimmung, wie sie ist.
public struct LearningState: Equatable, Sendable, Codable {
    /// Beginn der Lernphase; nur Nächte ab dann zählen.
    public private(set) var since: Date?
    /// Gelernte Werte; `nil` = noch keine.
    public private(set) var values: Learning.Values?
    /// Karte «Angepasst an dich» bestätigt.
    public private(set) var seen: Bool
    /// Letzte Änderung in ms seit 1970.
    public private(set) var updatedAtMs: Int64
    /// «Neu lernen» begonnen, während es schon Werte gab.
    public private(set) var relearnPending: Bool
    /// Zustimmung: an, aus/«Später», `nil` = noch nicht gefragt.
    public private(set) var consent: Bool?

    /// Noch nie begonnen, nicht gefragt.
    public init() {
        self.init(since: nil, values: nil, seen: false, updatedAtMs: 0, relearnPending: false, consent: nil)
    }

    /// Stand wie angegeben (Tests, Lesen).
    public init(since: Date?, values: Learning.Values?, seen: Bool, updatedAtMs: Int64, relearnPending: Bool = false, consent: Bool? = nil) {
        self.since = since
        self.values = values
        self.seen = seen
        self.updatedAtMs = updatedAtMs
        self.relearnPending = relearnPending
        self.consent = consent
    }

    /// «Neu lernen» läuft (es gibt bisherige Werte).
    public var relearning: Bool { values != nil && relearnPending }

    /// Läuft die Lernphase (zugestimmt, begonnen und noch keine Werte, oder «Neu lernen» läuft)? Nur dann zeichnet die
    /// Uhr nachts automatisch auf.
    public var active: Bool { consent == true && since != nil && (values == nil || relearning) }

    /// Noch nicht gefragt: Einrichtungsschritt bzw. Karte auf der Startseite «Lernphase starten» / «Später».
    public var askConsent: Bool { consent == nil }

    /// Paket für die Uhr.
    public var snapshot: LearningSnapshot {
        LearningSnapshot(active: active, floor: values?.floor, restingHeartRate: values?.restingHeartRate, updatedAtMs: updatedAtMs,
                         latencyMinutes: values?.latencyMinutes)
    }

    /// Zustimmung ab `now` (`on`: «Lernphase starten» bzw. Schalter an; sonst «Später» bzw. Schalter aus). An: Hat sie
    /// nie begonnen, beginnt sie jetzt; sonst läuft sie mit den bisher gezählten Nächten weiter. Aus: Die Uhr zeichnet
    /// nicht mehr automatisch auf; gelernte Werte bleiben.
    /// - Returns: ob die Lernphase jetzt begonnen hat.
    @discardableResult
    public mutating func setConsent(_ on: Bool, now: Date) -> Bool {
        consent = on
        var started = false
        if on, since == nil {
            since = now
            started = true
        }
        updatedAtMs = Self.millis(now)
        return started
    }

    /// Von vorn ab `now` («Verlauf löschen»): gelernte Werte weg, es zählen nur Nächte ab jetzt.
    public mutating func restart(now: Date) {
        clearValues()
        since = now
        seen = false
        updatedAtMs = Self.millis(now)
    }

    /// «Neu lernen» ab `now`: Es zählen nur Nächte ab jetzt; die bisherigen Werte gelten weiter, bis ``finish(_:now:)``
    /// sie ersetzt. Ohne bisherige Werte wie ``restart(now:)`` (ohne die Karte zurückzusetzen).
    public mutating func relearn(now: Date) {
        since = now
        if values != nil { relearnPending = true } else { clearValues() }
        updatedAtMs = Self.millis(now)
    }

    /// Fertig mit den gelernten Werten `learned`; nur, solange sie noch läuft (auch bei «Neu lernen»: dann ersetzen sie
    /// die bisherigen).
    /// - Returns: ob übernommen.
    @discardableResult
    public mutating func finish(_ learned: Learning.Values, now: Date) -> Bool {
        if since == nil || (values != nil && !relearnPending) { return false }
        clearValues()
        values = learned
        seen = false
        updatedAtMs = Self.millis(now)
        return true
    }

    /// Karte «Angepasst an dich» bestätigt.
    public mutating func setSeen() {
        seen = true
    }

    private mutating func clearValues() {
        values = nil
        relearnPending = false
    }

    private static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
}

/// Stand der Lernphase vom iPhone für die Uhr (wie `LearningSnapshot` auf Android): läuft sie (`active`: die Uhr
/// zeichnet nachts automatisch auf) und die gelernten Werte für den Detektor, je `nil` ohne Wert. Geht im
/// Anwendungskontext (``apply(to:)``); die Uhr übernimmt nur einen neueren Stand (`updatedAtMs`).
public struct LearningSnapshot: Equatable, Sendable, Codable {
    /// Lernphase läuft: Die Uhr zeichnet nachts automatisch auf.
    public let active: Bool
    /// Gelerntes Grundrauschen (m/s²).
    public let floor: Double?
    /// Gelernter Ruhepuls.
    public let restingHeartRate: Double?
    /// Zeitpunkt am iPhone (ms seit 1970).
    public let updatedAtMs: Int64
    /// Gelernte Einschlafdauer in Minuten, für die Schätzung bei «Gute Nacht» (``GoodNightEstimate``); nur Apple.
    /// Fehlt bei einem Stand älterer Versionen.
    public let latencyMinutes: Int?

    /// Kein Stand (älteres iPhone): keine Lernphase, keine Werte.
    public static let empty = LearningSnapshot(active: false, floor: nil, restingHeartRate: nil, updatedAtMs: 0)

    /// Stand wie angegeben.
    public init(active: Bool, floor: Double?, restingHeartRate: Double?, updatedAtMs: Int64, latencyMinutes: Int? = nil) {
        self.active = active
        self.floor = floor
        self.restingHeartRate = restingHeartRate
        self.updatedAtMs = updatedAtMs
        self.latencyMinutes = latencyMinutes
    }

    /// Aus dem Anwendungskontext; `nil`, wenn er keinen Stand trägt (älteres iPhone).
    public init?(context: [String: Any]) {
        guard let updatedAt = context[SyncKeys.learnUpdatedAt] as? Int64 else { return nil }
        self.init(
            active: (context[SyncKeys.learnActive] as? Bool) ?? false,
            // Ungültige Werte (kaputte oder fremde Nachricht) gelten als keine, wie `LearningSnapshot.decode` (Kotlin).
            floor: Learning.validFloor(context[SyncKeys.learnFloor] as? Double),
            restingHeartRate: Learning.validRestingHeartRate(context[SyncKeys.learnRestingHr] as? Double),
            updatedAtMs: updatedAt,
            latencyMinutes: (context[SyncKeys.learnLatency] as? Int64).map { Int($0) }
        )
    }

    /// Legt den Stand in den Anwendungskontext; fehlende Werte fehlen auch dort.
    public func apply(to context: inout [String: Any]) {
        context[SyncKeys.learnActive] = active
        context[SyncKeys.learnFloor] = floor.map { $0 as Any }
        context[SyncKeys.learnRestingHr] = restingHeartRate.map { $0 as Any }
        context[SyncKeys.learnLatency] = latencyMinutes.map { Int64($0) as Any }
        context[SyncKeys.learnUpdatedAt] = updatedAtMs
    }

    /// `config` mit den gelernten Werten (ohne Werte unverändert).
    public func applied(to config: DetectorConfig) -> DetectorConfig {
        config.withLearned(floor: floor, restingHeartRate: restingHeartRate)
    }

    /// Kurz fürs Diagnose-Protokoll: «active=1 floor=0.0200 hr=set» (kein Pulswert).
    public var diagLine: String {
        "active=\(active ? 1 : 0) floor=\(NoiseFloor.format(floor)) hr=\(restingHeartRate != nil ? "set" : "-")"
    }

    /// Ob `self` einen gespeicherten Stand `stored` ersetzt: nur, wenn er neuer ist (wie `LearningStore.setIfNewer`).
    public func replaces(_ stored: LearningSnapshot) -> Bool {
        updatedAtMs > stored.updatedAtMs
    }
}
