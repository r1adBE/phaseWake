/// Entscheidet Epoche für Epoche, ob jetzt ein guter Moment zum Wecken ist.
///
/// Regel siehe `docs/PHASENERKENNUNG.md`, Abschnitt 3. Ein Detektor gilt für genau ein Weckfenster;
/// für das nächste Fenster wird ein neuer erzeugt. Sobald er einmal geweckt hat, bleibt die
/// Entscheidung bestehen.
///
/// Reine Logik ohne Plattformabhängigkeit. Die Kotlin-Umsetzung (`android/core`) muss sich
/// identisch verhalten.
public final class WakeDetector {
    private static let currentHeartRateEpochs = 2

    /// Regel S5: Fenster (letzte Epochen inkl. aktueller), in dem eine Bewegung ≥ wirksame Wach-Schwelle genügt.
    private static let strongWindow = 2

    /// «Wach geht vor» (``awakeDuringBlock()``): Fenster in Epochen (5 Min), wie `AWAKE_BLOCK_WINDOW` (Kotlin).
    public static let awakeBlockWindow = 10

    /// «Wach geht vor»: nötige getrennte starke Bewegungen im ``awakeBlockWindow``.
    public static let awakeBlockEpisodes = 3

    /// Startwerte oder kalibrierte Schwellen; das Grundrauschen (``NoiseFloor``) hebt sie je Epoche höchstens an.
    /// Ändert sich nur über ``useOnsetFloor(_:)``.
    public private(set) var config: DetectorConfig

    /// Alle Bewegungen dieses Fensters inkl. Aufwärmphase, fürs Grundrauschen (``NoiseFloor/windowFloor(_:config:)``).
    private var motions: [Double?] = []

    /// Die letzten entscheidbaren Epochen, höchstens `config.maxWindow`.
    private var recent: [Epoch] = []

    /// Je Epoche in `recent` (gleiche Stelle): ob ihre Bewegung «Auf und Ab» zeigt
    /// (``MotionVariation/varies(previous:motion:floor:)``, mit dem Grundrauschen ihrer Epoche; ohne Grundrauschen
    /// `true`). Zählt nur für Regel S5 und die Leicht-Regel.
    private var recentVaries: [Bool] = []

    /// Die letzten ``awakeBlockWindow`` entscheidbaren Epochen, auch über übergangene Momente (``rearm()``) hinweg, für
    /// ``awakeDuringBlock()``: je Epoche, ob ihre Bewegung stark war (≥ die wirksame Wach-Schwelle, die für diese
    /// Epoche galt); `nil` = leere Bewegung.
    private var blockStrong: [Bool?] = []

    private var epochIndex = 0

    /// Pulse der ersten ``DetectorConfig/baselineEpochs`` Epochen (mindestens der Aufwärmphase), für den Basispuls.
    private var baselineHeartRates: [Double] = []

    /// Weckgrund, sobald entschieden; sonst `nil`.
    public private(set) var result: WakeReason?

    /// Erzeugt einen Detektor für genau ein Weckfenster.
    public init(config: DetectorConfig = .standard) {
        self.config = config
        recent.reserveCapacity(config.maxWindow)
    }

    /// Wertet die nächste Epoche aus.
    public func onEpoch(_ epoch: Epoch) -> Decision {
        if let result { return .wake(result) }

        let index = epochIndex
        epochIndex += 1
        motions.append(epoch.motion)
        // Basispuls aus den Epochen vor dieser; danach zählt der Puls dieser Epoche für die folgenden.
        let baseline = baselineHeartRate()
        if index < max(DetectorConfig.baselineEpochs, config.warmupEpochs), let heartRate = epoch.heartRate {
            baselineHeartRates.append(heartRate)
        }
        if index < config.warmupEpochs { return .wait }

        // Schwellen nach dem Grundrauschen bis und mit dieser Epoche; sie gelten für alle Epochen im Fenster der Regel.
        // Solange die Messung zu wenige Werte hat, gilt das bekannte (Einschlaf-Messung, sonst gelernt); es begrenzt
        // auch das des Fensters (``NoiseFloor/windowFloor(_:config:)``).
        let floor = NoiseFloor.windowFloor(motions, config: config)
        let wakeThreshold = NoiseFloor.wake(config: config, floor: floor)
        // «Auf und Ab» gegen die Bewegungen der laufenden Messung vor dieser Epoche (bleiben über ``rearm()`` hinweg).
        var varies = true
        if let floor, let motion = epoch.motion {
            varies = MotionVariation.varies(previous: Array(motions.dropLast()), motion: motion, floor: floor)
        }
        if recent.count == config.maxWindow {
            recent.removeFirst()
            recentVaries.removeFirst()
        }
        recent.append(epoch)
        recentVaries.append(varies)
        // «Wach geht vor» bewertet jede Epoche mit der Wach-Schwelle ihrer Zeit (Audit 23.11.2026).
        if blockStrong.count == Self.awakeBlockWindow { blockStrong.removeFirst() }
        blockStrong.append(epoch.motion.map { $0 >= wakeThreshold })
        // Die Wach-Regel zählt jede Bewegung, auch ohne «Auf und Ab».
        if hits(window: config.awakeWindow, threshold: wakeThreshold, needsVariation: false) >= config.awakeHits {
            return wake(.awake)
        }
        // Leichter Schlaf erst mit Grundrauschen (4 Werte oder Vorwert): Ohne zählte das Rauschen einer
        // Uhr wie der Galaxy Watch (ruhig 0.02) gegen die Startschwelle 0.002 als Bewegung, und es entschied allein der
        // Puls. Regel S5 («Bewegung zählt»): Eine starke Bewegung (≥ wirksame Wach-Schwelle) in den letzten
        // `strongWindow` Epochen weckt ohne Pulsbedingung. Die Leicht-Regel (2 leichte Bewegungen) braucht den
        // Pulsanstieg, sonst weckte sie schon auf Rauschen. Die Leicht-Schwelle liegt nie über der des gelernten
        // Grundrauschens (``NoiseFloor/windowLight(config:floor:)``). Beide zählen nur Bewegungen mit «Auf und Ab»
        // (``MotionVariation``).
        guard floor != nil else { return .wait }
        let strong = hits(window: Self.strongWindow, threshold: wakeThreshold, needsVariation: true) >= 1
        let lightThreshold = NoiseFloor.windowLight(config: config, floor: floor)
        let lightHits = hits(window: config.lightWindow, threshold: lightThreshold, needsVariation: true)
        let light = lightHits >= config.lightHits
        if strong || (light && heartRateRisen(baseline)) {
            return wake(.lightSleep)
        }
        return .wait
    }

    /// Zielzeit erreicht: liefert den bestehenden Grund oder weckt mit ``WakeReason/deadline``.
    @discardableResult
    public func finish() -> WakeReason {
        if let result { return result }
        result = .deadline
        return .deadline
    }

    /// Zählt unter den letzten `window` Epochen jene mit Bewegung ≥ `threshold`, mit `needsVariation` nur solche mit
    /// «Auf und Ab» (`recentVaries`). Leere Bewegung zählt nie.
    private func hits(window: Int, threshold: Double, needsVariation: Bool) -> Int {
        var count = 0
        for i in max(0, recent.count - window)..<recent.count {
            if let motion = recent[i].motion, motion >= threshold, !needsVariation || recentVaries[i] { count += 1 }
        }
        return count
    }

    /// Basispuls: Median der Pulse aus den ersten ``DetectorConfig/baselineEpochs`` Epochen des Fensters, sobald es
    /// mindestens ``DetectorConfig/minBaselineHeartRates`` sind; mit gelerntem Ruhepuls
    /// (``DetectorConfig/restingHeartRate``) begrenzt auf Ruhepuls bis ``DetectorConfig/restingClampFactor`` ×
    /// Ruhepuls. Sonst Ruhepuls × ``DetectorConfig/restingFallbackFactor``, ohne Ruhepuls `nil`. Identisch zu Kotlin.
    private func baselineHeartRate() -> Double? {
        let resting = config.restingHeartRate
        if baselineHeartRates.count < DetectorConfig.minBaselineHeartRates {
            return resting.map { $0 * DetectorConfig.restingFallbackFactor }
        }
        let sorted = baselineHeartRates.sorted()
        let mid = sorted.count / 2
        let median = sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0
        guard let resting else { return median }
        return min(max(median, resting), resting * DetectorConfig.restingClampFactor)
    }

    /// Pulsbedingung: aktueller Puls (Mittel der letzten zwei Epochen) liegt mindestens um
    /// `heartRateRise` über dem Basispuls `baseline` (``baselineHeartRate()``). Fehlt einer der beiden Werte, ist sie
    /// nicht erfüllt: Leichte Bewegung allein reicht für ``WakeReason/lightSleep`` nicht («Phase nicht behaupten»).
    private func heartRateRisen(_ baseline: Double?) -> Bool {
        guard let baseline else { return false }
        var sum = 0.0
        var count = 0
        for epoch in recent.suffix(Self.currentHeartRateEpochs) {
            if let heartRate = epoch.heartRate {
                sum += heartRate
                count += 1
            }
        }
        if count == 0 { return false }
        return sum / Double(count) >= baseline * (1.0 + config.heartRateRise)
    }

    /// Übergangener Moment (kurze Nacht, ``ShortNight/blocksEarly(onset:now:sleepGoalMinutes:target:measured:)``): Die
    /// Entscheidung wird verworfen, die Erkennung läuft weiter und kann einen neuen Moment melden. Die Treffer des
    /// übergangenen Moments zählen nicht mehr: Die Regeln sehen nur Epochen nach dieser Grenze, ein neuer Moment
    /// braucht neue Treffer (sonst weckte der alte, sobald die Sperre endet). Grundrauschen, Basispuls und die
    /// Bewegungen für «Auf und Ab» (``MotionVariation``) bleiben. Identisch zu `WakeDetector.rearm` (Kotlin).
    public func rearm() {
        result = nil
        recent.removeAll(keepingCapacity: true)
        recentVaries.removeAll(keepingCapacity: true)
    }

    /// «Wach geht vor» während der Sperre der kurzen Nacht (`docs/PHASENERKENNUNG.md` Abschnitt 8): mindestens
    /// ``awakeBlockEpisodes`` getrennte starke Bewegungen (≥ wirksame Wach-Schwelle, die für diese Epoche galt) in den
    /// letzten ``awakeBlockWindow`` Epochen, auch über übergangene Momente hinweg. Getrennt heisst: mindestens eine
    /// Epoche darunter dazwischen; eine leere Epoche trennt nicht. Sinkt die Schwelle im Fenster, zählen frühere
    /// Bewegungen nicht nachträglich als stark (Audit 23.11.2026). Identisch zu `WakeDetector.awakeDuringBlock`
    /// (Kotlin).
    public func awakeDuringBlock() -> Bool {
        var episodes = 0
        var strong = false
        for flag in blockStrong {
            guard let now = flag else { continue }
            if now && !strong { episodes += 1 }
            strong = now
        }
        return episodes >= Self.awakeBlockEpisodes
    }

    /// Moment während der Sperre der kurzen Nacht: Ist die Person wach (``awakeDuringBlock()``), gilt er mit
    /// ``WakeReason/awake``; sonst wird er übergangen (``rearm()``) und das Ergebnis ist `nil`. Identisch zu Kotlin.
    public func onBlocked() -> WakeReason? {
        if awakeDuringBlock() {
            result = .awake
            return .awake
        }
        rearm()
        return nil
    }

    /// Grundrauschen der Einschlaf-Messung dieser Nacht, das erst nach Fensterbeginn bekannt wird (die Apple Watch
    /// rechnet es zu Beginn der Sitzung nach, ``RecorderOnset``): gilt ab der nächsten Epoche wie
    /// ``DetectorConfig/withOnsetFloor(_:)``. Identisch zu Kotlin.
    public func useOnsetFloor(_ floor: Double?) {
        config = config.withOnsetFloor(floor)
    }

    private func wake(_ reason: WakeReason) -> Decision {
        result = reason
        return .wake(reason)
    }
}
