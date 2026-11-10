import Foundation

/// Lernphase (Entscheid r1AD 09.11.2026, Apple seit 10.11.2026): Die Uhr zeichnet ``nights`` Nächte auf, danach passt
/// Phase Wake den Wecker an: eigenes Grundrauschen (``NoiseFloor``, ``DetectorConfig/personalFloor``), eigener Ruhepuls
/// (``DetectorConfig/restingHeartRate``) und die Einschlafdauer für die Empfehlung der Schlafenszeit (``bedtimeRange``).
/// Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 10. Reine Logik; identisch zu `Learning` (Kotlin).
public enum Learning {
    /// So viele gezählte Nächte braucht die Lernphase (keine Kalendertage).
    public static let nights = 7

    /// Eine Nacht zählt ab so vielen Epochen mit Bewegung (36 Min): 3 Std. in Stichproben (60 s alle 5 Min) ergeben
    /// 36 Stichproben = 72 Epochen (Entscheid r1AD 10.11.2026).
    public static let minMeasuredEpochs = 72

    /// … und die Epochen mit Bewegung müssen mindestens so viele Epochen der Nacht überspannen (3 Std., erste bis letzte).
    public static let minSpanEpochs = 360

    /// Die Uhr zeichnet in der Lernphase nur ab diesem Akkustand (Prozent) automatisch auf; der Wecker hat Vorrang.
    public static let minBattery = 50

    /// Ein Ruhepuls je Nacht erst ab so vielen Epochen mit Puls (Galaxy Watch: 30 Min, in Stichproben rund 2½ Std.);
    /// sonst wäre das 10. Perzentil zu zufällig.
    public static let minHeartRateEpochs = 60

    /// Apple Watch: Der Puls kommt aus Apple Health, wo die Uhr in Ruhe etwa alle 5 Min einen Wert ablegt (nicht
    /// garantiert). Darum genügen hier so viele Epochen mit Puls: ein Wert je 5 Min über 3 Std., wie die Stichproben
    /// einer gezählten Nacht (``minMeasuredEpochs`` / 2).
    public static let minHeartRateEpochsApple = 36

    /// Ruhepuls und Einschlafdauer erst aus so vielen Nächten mit Werten; sonst bleiben sie ungelernt.
    public static let minValueNights = 3

    /// Einschlafdauer einer Nacht höchstens so lang (Minuten): Ein spätes Zubettgehen soll die Empfehlung nicht verschieben.
    public static let maxLatencyMinutes = 60

    /// Breite der empfohlenen Schlafenszeit in Minuten.
    public static let bedtimeRangeMinutes = 30

    /// Die empfohlene Schlafenszeit endet auf ganzen so vielen Minuten (abgerundet).
    public static let roundMinutes = 5

    /// Eine aufgezeichnete Nacht: ab `start` je 30-s-Epoche, dazu, falls bekannt, Beginn der Einschlafdauer `bedAt` und
    /// Einschlafzeit `asleepAt`.
    public struct Night: Equatable, Sendable {
        /// Beginn der Aufzeichnung (Epoche 0).
        public let start: Date
        /// Epochen ab `start`.
        public let epochs: [Epoch]
        /// Beginn der Einschlafdauer; `nil` = unbekannt.
        public let bedAt: Date?
        /// Einschlafzeit; `nil` = unbekannt.
        public let asleepAt: Date?

        /// Nacht ab `start` mit `epochs`, optional mit Beginn der Einschlafdauer und Einschlafzeit.
        public init(start: Date, epochs: [Epoch], bedAt: Date? = nil, asleepAt: Date? = nil) {
            self.start = start
            self.epochs = epochs
            self.bedAt = bedAt
            self.asleepAt = asleepAt
        }
    }

    /// Gelernte Werte: Grundrauschen `floor` (m/s²), Ruhepuls `restingHeartRate`, Einschlafdauer `latencyMinutes`, je
    /// `nil`, wenn zu wenige Werte da waren; `nights` = gezählte Nächte.
    public struct Values: Equatable, Sendable, Codable {
        /// Grundrauschen der Bewegung (m/s²).
        public let floor: Double?
        /// Ruhepuls (Schläge pro Minute).
        public let restingHeartRate: Double?
        /// Einschlafdauer in ganzen Minuten.
        public let latencyMinutes: Int?
        /// Gezählte Nächte, aus denen gelernt wurde.
        public let nights: Int

        /// Werte wie angegeben.
        public init(floor: Double?, restingHeartRate: Double?, latencyMinutes: Int?, nights: Int) {
            self.floor = floor
            self.restingHeartRate = restingHeartRate
            self.latencyMinutes = latencyMinutes
            self.nights = nights
        }
    }

    /// Epochen mit Bewegung.
    public static func measuredEpochs(_ night: Night) -> Int {
        night.epochs.filter { $0.motion != nil }.count
    }

    /// Ob `night` zählt: mindestens ``minMeasuredEpochs`` Epochen mit Bewegung, und von der ersten bis zur letzten von
    /// ihnen mindestens ``minSpanEpochs`` Epochen (gilt für Stichproben wie für durchgehende Aufzeichnungen).
    public static func counts(_ night: Night) -> Bool {
        guard let first = night.epochs.firstIndex(where: { $0.motion != nil }),
              let last = night.epochs.lastIndex(where: { $0.motion != nil }) else { return false }
        return measuredEpochs(night) >= minMeasuredEpochs && last - first + 1 >= minSpanEpochs
    }

    /// Gezählte Nächte der Lernphase ab `since`: Beginn nicht vor `since`, ``counts(_:)``, je Nachtdatum
    /// (``SleepNotes/nightDate(_:calendar:)`` des Beginns) nur eine, die mit den meisten Epochen mit Bewegung (bei
    /// Gleichstand die erste). Älteste zuerst.
    public static func counted(_ nights: [Night], since: Date, calendar: Calendar) -> [Night] {
        var best: [String: Night] = [:]
        for night in nights where night.start >= since && counts(night) {
            let date = SleepNotes.nightDate(night.start, calendar: calendar)
            if let kept = best[date], measuredEpochs(kept) >= measuredEpochs(night) { continue }
            best[date] = night
        }
        return best.values.sorted { $0.start < $1.start }
    }

    /// «Nacht N von 7» bei `counted` gezählten Nächten: die laufende Nacht, höchstens ``nights``.
    public static func progress(_ counted: Int) -> Int {
        min(max(counted + 1, 1), nights)
    }

    /// Ob die Lernphase mit `counted` gezählten Nächten fertig ist.
    public static func finished(_ counted: Int) -> Bool {
        counted >= nights
    }

    /// Grundrauschen einer Nacht nach ``NoiseFloor/floor(_:personal:)`` aus allen ihren Epochen.
    public static func nightFloor(_ night: Night) -> Double? {
        NoiseFloor.floor(night.epochs.map(\.motion))
    }

    /// Ruhepuls einer Nacht: 10. Perzentil der vorhandenen Pulse (Rangmethode, aufsteigend, Position ⌈0.1 · n⌉, gezählt
    /// ab 1); `nil` unter `minEpochs` Epochen mit Puls (Galaxy Watch ``minHeartRateEpochs``, Apple Watch
    /// ``minHeartRateEpochsApple``).
    public static func nightRestingHeartRate(_ night: Night, minEpochs: Int = Learning.minHeartRateEpochs) -> Double? {
        let values = night.epochs.compactMap(\.heartRate).sorted()
        guard values.count >= minEpochs, !values.isEmpty else { return nil }
        // ⌈0.1 · n⌉ ganzzahlig gerechnet, damit Kotlin und Swift exakt dieselbe Position wählen.
        return values[(values.count + 9) / 10 - 1]
    }

    /// Einschlafdauer einer Nacht in ganzen Minuten (abgeschnitten): von `bedAt` bis `asleepAt`, höchstens
    /// ``maxLatencyMinutes``. `nil` ohne beide Zeiten oder wenn die Einschlafzeit davor liegt (dann ist unbekannt, wann
    /// die Person ins Bett ging).
    public static func nightLatencyMinutes(_ night: Night) -> Int? {
        guard let bed = night.bedAt, let asleep = night.asleepAt, asleep >= bed else { return nil }
        let millis = Int64((asleep.timeIntervalSince1970 * 1000).rounded()) - Int64((bed.timeIntervalSince1970 * 1000).rounded())
        return Int(min(millis / 60_000, Int64(maxLatencyMinutes)))
    }

    /// Gelernte Werte aus den gezählten Nächten `counted`: Median der Grundrauschen, Median der Ruhepulse (ab
    /// ``minValueNights`` Nächten mit Ruhepuls, je Nacht ab `minHeartRateEpochs` Epochen mit Puls), Median der
    /// Einschlafdauern (ab ``minValueNights`` Nächten mit Wert, gerundet).
    public static func learn(_ counted: [Night], minHeartRateEpochs: Int = Learning.minHeartRateEpochs) -> Values {
        let floors = counted.compactMap(nightFloor)
        let pulses = counted.compactMap { nightRestingHeartRate($0, minEpochs: minHeartRateEpochs) }
        let latencies = counted.compactMap(nightLatencyMinutes)
        return Values(
            floor: median(floors),
            restingHeartRate: pulses.count >= minValueNights ? median(pulses) : nil,
            // Wie `Math.round` in Kotlin: halbe Minuten aufwärts.
            latencyMinutes: latencies.count >= minValueNights ? median(latencies.map { Double($0) }).map { Int(($0 + 0.5).rounded(.down)) } : nil,
            nights: counted.count
        )
    }

    /// Median: mittlerer Wert, bei gerader Anzahl das Mittel der beiden mittleren; `nil` ohne Werte.
    public static func median(_ values: [Double]) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /// `config` mit den gelernten Werten `values` (ohne gelernte Werte unverändert).
    public static func withLearned(_ config: DetectorConfig, _ values: Values?) -> DetectorConfig {
        guard let values else { return config }
        return config.withLearned(floor: values.floor, restingHeartRate: values.restingHeartRate)
    }

    /// Ob das gelernte Grundrauschen `floor` die Leicht-Schwelle von `config` anhebt (für die Anzeige in Worten).
    public static func raisesThresholds(_ floor: Double?, config: DetectorConfig = .standard) -> Bool {
        NoiseFloor.light(config: config, floor: floor.map { min($0, NoiseFloor.maxFloor) }) > config.lightThreshold
    }

    /// Empfohlene Schlafenszeit vor der Zielzeit `target`: spätestens Zielzeit − Schlaf `sleepMinutes` (Schlafziel bzw.
    /// Schlafdauer des Weckers) − Einschlafdauer `latencyMinutes` (ungelernt 0), abgerundet auf ``roundMinutes``;
    /// frühestens ``bedtimeRangeMinutes`` davor. Feste Dauern wie ``Bedtime``.
    /// - Returns: (frühestens, spätestens).
    public static func bedtimeRange(target: Date, sleepMinutes: Int, latencyMinutes: Int?) -> (from: Date, until: Date) {
        let latest = target.addingTimeInterval(-TimeInterval((sleepMinutes + (latencyMinutes ?? 0)) * 60))
        let step = Int64(roundMinutes * 60_000)
        let millis = Int64((latest.timeIntervalSince1970 * 1000).rounded(.down))
        // Abrunden wie `Math.floorDiv` (auch vor 1970).
        let rounded = millis - ((millis % step) + step) % step
        let until = Date(timeIntervalSince1970: TimeInterval(rounded) / 1000)
        return (from: until.addingTimeInterval(-TimeInterval(bedtimeRangeMinutes * 60)), until: until)
    }

    /// Apple: Beginn der Einschlafdauer und Einschlafzeit einer Aufzeichnung ab `start` aus Apple Health. `inBed` sind
    /// die Anfänge der Proben «Im Bett», `asleep` die der Schlafproben (jeder Phase), beide aus der Zeit der
    /// Aufzeichnung. Beginn = früheste «Im Bett», frühestens `start` (wie die Bettzeit der automatischen Messung auf
    /// Android); Einschlafzeit = früheste Schlafprobe. Liegt sie vor dem Beginn, verwirft ``nightLatencyMinutes(_:)``
    /// die Nacht. `nil`, wenn eines fehlt.
    public static func healthOnset(start: Date, inBed: [Date], asleep: [Date]) -> (bedAt: Date, asleepAt: Date)? {
        guard let bed = inBed.min(), let sleep = asleep.min() else { return nil }
        return (bedAt: max(bed, start), asleepAt: sleep)
    }
}
