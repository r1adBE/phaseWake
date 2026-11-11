import Foundation

/// Apple Watch: Einschlafzeit aus den Werten des Bewegungs-Rekorders (`docs/PHASENERKENNUNG.md`, Abschnitte 6 und 8).
///
/// Die Galaxy Watch misst das Einschlafen ab 60 Min vor der Bettzeit bzw. ab «Gute Nacht» laufend (`OnsetMonitor`,
/// Kotlin). Eine App auf der Apple Watch kann das nicht; die Uhr rechnet darum zu Beginn der Smart-Alarm-Sitzung
/// dieselbe Regel über die schon aufgezeichneten Epochen nach, Epoche für Epoche, als liefe die Messung durchgehend:
/// - Ruhig heisst Bewegung vorhanden und unter der wirksamen Leicht-Schwelle (``NoiseFloor/light(threshold:floor:)``
///   mit dem Grundrauschen der Epochen bis dahin, solange es zu wenige sind dem gelernten); 15 Min Ruhe am Stück
///   (``SleepOnset``), Einschlafzeit = Beginn der Strecke.
/// - Zeitgrenze: ``maxDuration`` nach dem Beginn bzw. ``giveUpLead`` vor der eingestellten Weckzeit. An der Höchstdauer
///   verlängert sich die Messung bei Unruhe in den letzten 15 Min (``restless(_:threshold:)``) um je ``extendStep``,
///   höchstens bis ``extendCap(since:windowStart:)``. Den Akku dieser Zeit kennt die Uhr nicht: Er hindert hier nicht
///   (wie ein unbekannter Akku auf der Galaxy Watch).
///
/// Identisch zu `OnsetMonitor` mit durchgehender Messung und `OnsetRules` (Kotlin, Uhr).
public enum RecorderOnset {
    /// Längste Messung ohne Verlängerung (wie `OnsetMonitor.MAX_DURATION`).
    public static let maxDuration: TimeInterval = 2 * 3600
    /// Längste Messung mit Verlängerungen, ab dem Beginn (wie `OnsetMonitor.MAX_EXTENDED`).
    public static let maxExtended: TimeInterval = 4 * 3600
    /// Eine Verlängerung (wie `OnsetMonitor.EXTEND_STEP`).
    public static let extendStep: TimeInterval = 15 * 60
    /// So lange vor der eingestellten Weckzeit gibt die Messung auf (wie `WakeService.ONSET_GIVE_UP`).
    public static let giveUpLead: TimeInterval = 15 * 60
    /// Epochen der letzten 15 Min für die Unruhe (wie `OnsetRules.RECENT_EPOCHS`).
    public static let recentEpochs = 30

    /// Ergebnis der Nachrechnung.
    public enum Result: Equatable, Sendable {
        /// Eingeschlafen um `at` (Beginn der ruhigen Strecke, Epoche `index` ab dem Beginn).
        case asleep(at: Date, index: Int)
        /// Zeitgrenze erreicht, ohne Einschlafen: `reason` «give up» bzw. «give up (max duration)».
        case gaveUp(at: Date, reason: String)
        /// Die Werte enden vor einer Entscheidung (das Weckfenster hat begonnen): Einschlafzeit unbekannt.
        case open
    }

    /// Späteste Verlängerung: Beginn plus ``maxExtended``, nie über den Beginn des Weckfensters.
    public static func extendCap(since: Date, windowStart: Date) -> Date {
        min(since.addingTimeInterval(maxExtended), windowStart)
    }

    /// Rechnet die Messung ab `since` nach. `motions`: Bewegung je 30-s-Epoche ab `since` (leer = keine Werte);
    /// `threshold`: Leicht-Schwelle (Startwert oder kalibriert); `personalFloor`: gelerntes Grundrauschen; `latest`:
    /// eingestellte Weckzeit der Nacht; `extendCap`: späteste Verlängerung (`nil` = keine).
    public static func find(motions: [Double?], since: Date, threshold: Double, personalFloor: Double?, latest: Date,
                            extendCap: Date?) -> Result {
        let latestGiveUp = latest.addingTimeInterval(-giveUpLead)
        var maxEnd = since.addingTimeInterval(maxDuration)
        var floor: Double?
        var seen: [Double?] = []
        seen.reserveCapacity(motions.count)
        for (index, motion) in motions.enumerated() {
            // Die Epoche ist bei ihrem Ende da; zuerst die Zeitgrenze wie beim Eintreffen der Werte auf der Galaxy Watch.
            let now = since.addingTimeInterval(TimeInterval((index + 1) * DetectorConfig.epochSeconds))
            if now >= min(latestGiveUp, maxEnd) {
                let busy = restless(seen, threshold: NoiseFloor.light(threshold: threshold, floor: floor))
                guard let cap = extendCap,
                      let until = extendUntil(at: now, maxEnd: maxEnd, latestGiveUp: latestGiveUp, cap: cap, restless: busy) else {
                    return .gaveUp(at: now, reason: giveUpReason(at: now, latestGiveUp: latestGiveUp, maxEnd: maxEnd) ?? "give up")
                }
                maxEnd = until
            }
            seen.append(motion)
            floor = NoiseFloor.floor(seen, personal: personalFloor)
            if let start = SleepOnset.find(seen, threshold: NoiseFloor.light(threshold: threshold, floor: floor)) {
                return .asleep(at: since.addingTimeInterval(TimeInterval(start * DetectorConfig.epochSeconds)), index: start)
            }
        }
        return .open
    }

    /// Unruhig: In den letzten `recent` Epochen hat mindestens eine Bewegung an oder über `threshold`; leere zählen
    /// nicht (wie `OnsetRules.restless` mit lauter gemessenen Epochen).
    public static func restless(_ motions: [Double?], threshold: Double, recent: Int = recentEpochs) -> Bool {
        motions.suffix(recent).contains { motion in motion.map { $0 >= threshold } ?? false }
    }

    /// Neues Ende bei spätem Einschlafen oder `nil` (aufgeben): nur an der Höchstdauer `maxEnd` (nicht an der Grenze vor
    /// der Weckzeit) und bei Unruhe; dann `at` + ``extendStep``, höchstens `cap` und `latestGiveUp` (wie
    /// `OnsetRules.extendUntil` mit unbekanntem Akku).
    public static func extendUntil(at: Date, maxEnd: Date, latestGiveUp: Date, cap: Date, restless: Bool) -> Date? {
        if at < maxEnd || maxEnd > latestGiveUp || !restless { return nil }
        let until = min(at.addingTimeInterval(extendStep), cap, latestGiveUp)
        return until > at ? until : nil
    }

    /// «give up (max duration)» bzw. «give up», wenn `at` die frühere der beiden Grenzen erreicht hat; sonst `nil`
    /// (wie `OnsetRules.giveUpReason`).
    public static func giveUpReason(at: Date, latestGiveUp: Date, maxEnd: Date) -> String? {
        if at < min(latestGiveUp, maxEnd) { return nil }
        return maxEnd <= latestGiveUp ? "give up (max duration)" : "give up"
    }

    /// Einschlafzeit aus der erkannten ruhigen Strecke ab `found`: nie vor dem Beginn der Einschlafdauer `bedAt`
    /// (``bedAt(since:automatic:latest:sleepMinutes:)``, automatisch die Bettzeit). Die automatische Messung beginnt 60 Min
    /// vor der Bettzeit; wer dann schon still liegt (Sofa, Lesen), ist nicht vor der Bettzeit eingeschlafen – sonst rückte
    /// die Zielzeit vor und die Untergrenze kurzer Nächte fiele zu früh weg. Nach «Gute Nacht» ist `bedAt` der Beginn der
    /// Messung; dann gilt `found` unverändert. Identisch zu `OnsetRules.onsetAt` (Kotlin).
    public static func onsetAt(found: Date, bedAt: Date) -> Date {
        max(found, bedAt)
    }

    /// Beginn der Einschlafdauer (Lernphase, Schlafwert): nach «Gute Nacht» (`automatic` falsch) `since`, sonst die
    /// Bettzeit (`latest` minus `sleepMinutes`), frühestens `since` (wie `OnsetRules.bedAt`).
    public static func bedAt(since: Date, automatic: Bool, latest: Date, sleepMinutes: Int) -> Date {
        automatic ? max(since, latest.addingTimeInterval(-TimeInterval(sleepMinutes * 60))) : since
    }
}

/// Apple Watch, Einschlaf-Modus («nach 7½ Std.»): «Gute Nacht» legt die Zielzeit sofort fest, aus der Zeit des Tippens,
/// der gelernten Einschlafdauer (sonst ``defaultLatencyMinutes``) und der Schlafdauer, nie später als die eingestellte
/// Weckzeit (Näherung, freigegeben von r1AD 10.11.2026). Die Galaxy Watch misst das Einschlafen stattdessen und legt die
/// Zielzeit danach fest (`GoodNight`, Kotlin). Am Morgen vergleicht die Uhr die Schätzung mit der Einschlafzeit aus dem
/// Rekorder (``RecorderOnset``) im Diagnose-Protokoll.
public enum GoodNightEstimate {
    /// Einschlafdauer, solange keine gelernt ist (Minuten).
    public static let defaultLatencyMinutes = 15
    /// «Gute Nacht» nur, wenn die eingestellte Weckzeit höchstens so weit entfernt ist (wie `WakeRules.GOOD_NIGHT_MAX_AHEAD`).
    public static let maxAhead: TimeInterval = 16 * 3600

    /// Geschätzte Einschlafzeit: `tap` plus gelernte Einschlafdauer `latencyMinutes` (ohne: ``defaultLatencyMinutes``).
    public static func asleep(tap: Date, latencyMinutes: Int?) -> Date {
        tap.addingTimeInterval(TimeInterval((latencyMinutes ?? defaultLatencyMinutes) * 60))
    }

    /// Zielzeit: geschätzte Einschlafzeit plus Schlafdauer, nie später als `latest` (``AlarmSchedule/sleepTarget(onset:sleepDurationMinutes:latest:)``).
    public static func target(tap: Date, latencyMinutes: Int?, sleepDurationMinutes: Int, latest: Date) -> Date {
        AlarmSchedule.sleepTarget(onset: asleep(tap: tap, latencyMinutes: latencyMinutes), sleepDurationMinutes: sleepDurationMinutes,
                                  latest: latest)
    }

    /// Ob «Gute Nacht» für die eingestellte Weckzeit `latest` zu früh kommt (mehr als ``maxAhead`` davor) oder sie fehlt.
    public static func tooFar(now: Date, latest: Date?) -> Bool {
        guard let latest else { return true }
        return latest.timeIntervalSince(now) > maxAhead
    }

    /// Bis wann die Nacht mit Zielzeit `target` erledigt ist: die eingestellte Weckzeit `nominal` derselben Nacht, auch
    /// wenn früher geweckt wurde (höchstens ``maxAhead`` später); sonst `target` (wie `WakeRules.doneUntil`).
    public static func doneUntil(target: Date, nominal: Date?) -> Date {
        if let nominal, nominal > target, nominal.timeIntervalSince(target) <= maxAhead { return nominal }
        return target
    }
}
