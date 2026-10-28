import Foundation

/// Einschlafklänge in der Reihenfolge der Anzeige, identisch zu Kotlin. Rohwert = Speicher (ältere Werte
/// bleiben gültig).
public enum SleepSound: String, CaseIterable, Sendable {
    case white = "WHITE"
    case pink = "PINK"
    case brown = "BROWN"
    case fan = "FAN"
    case binaural = "BINAURAL"
    case rain = "RAIN"
    case ocean = "OCEAN"
    case forest = "FOREST"
    case fire = "FIRE"
    case brook = "BROOK"

    /// `true` = Aufnahme (CC0), nahtlos in Schleife gespielt; Datei «sound_<rohwert klein>.m4a» im App-Bundle.
    /// `false` = im Code erzeugt (`NoiseGenerator`).
    public var recorded: Bool {
        switch self {
        case .white, .pink, .brown, .fan, .binaural: false
        case .rain, .ocean, .forest, .fire, .brook: true
        }
    }

    /// Verstärkung beim Abspielen in dB: Die Aufnahmen klingen rund 5–8 dB leiser als die erzeugten Klänge,
    /// darum +6 dB. Ihre Spitzen liegen bei höchstens −6 dBFS (make-sound-loops.py); `SleepSounds.amplify`
    /// begrenzt danach trotzdem.
    public var gainDb: Double { recorded ? SleepSounds.recordingGainDb : 0 }

    /// `gainDb` als Faktor für die Abtastwerte.
    public var gain: Float { Float(pow(10, gainDb / 20)) }
}

/// Zeitplan der Einschlafklänge: Timer, Ein- und Ausblenden, spätestens Ende beim Weckfenster.
public enum SleepSounds {
    /// Timer-Auswahl in Minuten; `nil` = ohne Timer.
    public static let timerOptions: [Int?] = [15, 30, 60, nil]
    public static let defaultTimerMinutes = 30
    public static let fadeInSeconds = 3.0
    public static let fadeOutSeconds = 30.0
    /// Verstärkung der Aufnahmen beim Abspielen (`SleepSound.gainDb`).
    public static let recordingGainDb = 6.0
    /// Obergrenze nach der Verstärkung: −0,5 dBFS, damit nichts übersteuert.
    public static let ceiling: Float = 0.9440609

    /// Abtastwert mal `gain`, auf ±`ceiling` begrenzt (gegen Übersteuern bei lauten Stellen).
    @inline(__always)
    public static func amplify(_ sample: Float, gain: Float) -> Float {
        min(ceiling, max(-ceiling, sample * gain))
    }

    /// Wann die Klänge enden: nach dem Timer, spätestens beim Beginn des nächsten Weckfensters
    /// (sie sollen das Wecken nie überdecken). `nil` = ohne Ende.
    public static func stopAt(start: Date, timerMinutes: Int?, nextWindowStart: Date?) -> Date? {
        let timerEnd = timerMinutes.map { start.addingTimeInterval(TimeInterval($0 * 60)) }
        switch (timerEnd, nextWindowStart) {
        case let (t?, w?): return min(t, w)
        case let (t?, nil): return t
        case let (nil, w?): return w
        case (nil, nil): return nil
        }
    }

    /// Lautstärkefaktor 0…1 nach `elapsed` Sekunden: 3 s einblenden, die letzten 30 s vor `total`
    /// ausblenden (bei kurzer Dauer entsprechend kürzer). `total` = Spieldauer in Sekunden oder `nil`.
    public static func gain(elapsed: Double, total: Double?, fadeOut: Double = fadeOutSeconds) -> Double {
        guard elapsed >= 0 else { return 0 }
        let fadeIn = min(1, elapsed / fadeInSeconds)
        guard let total else { return fadeIn }
        let remaining = total - elapsed
        guard remaining > 0 else { return 0 }
        let out = fadeOut > 0 ? min(1, remaining / fadeOut) : 1
        return min(fadeIn, out)
    }
}

/// Rauschgenerator für die erzeugten Einschlafklänge (`recorded == false`): deterministisch je Startwert,
/// nahtlos (endloser Strom, keine Schleife), ohne Gleichanteil und mit Spitzen unter −12 dBFS. Ausgabe mit
/// `channels` Kanälen, verschachtelt (links, rechts, links …).
public struct NoiseGenerator: Sendable {
    /// −12 dBFS.
    public static let peak = 0.251

    public let sound: SleepSound
    public let sampleRate: Double

    private var rng: UInt64
    // Rosa Rauschen (Filter nach Paul Kellet)
    private var p0 = 0.0, p1 = 0.0, p2 = 0.0
    // Braunes Rauschen (leckender Integrator)
    private var brownState = 0.0
    // Gleichanteil-Filter
    private var dcIn = 0.0, dcOut = 0.0
    private let dcPole: Double
    // Ventilator: braunes Rauschen durch einen Tiefpass (~300 Hz), dazu leises Brummen (50 und 100 Hz).
    private var fanLow = 0.0
    private let fanPole: Double
    private var humPhase = 0.0
    // Einschlaf-Wellen: je Ohr ein Sinus (links 200 Hz, rechts 202 Hz), dazu sehr leises rosa Rauschen.
    private var leftPhase = 0.0, rightPhase = 0.0
    private var rightPending = false
    private var right: Float = 0
    /// Spitze des Sinus: RMS = 0,0447 / √2 ≈ 0,032 (−30 dBFS).
    private static let binauralAmp = 0.0447

    /// Kanäle: 2 für die Einschlaf-Wellen (je Ohr ein anderer Ton), sonst 1.
    public var channels: Int { sound == .binaural ? 2 : 1 }

    public init(sound: SleepSound, sampleRate: Double = 48_000, seed: UInt64 = 1) {
        self.sound = sound
        self.sampleRate = sampleRate
        rng = seed &+ 0x9E37_79B9_7F4A_7C15
        // Hochpass um 5 Hz gegen jeden Gleichanteil.
        dcPole = exp(-2 * Double.pi * 5 / sampleRate)
        fanPole = exp(-2 * Double.pi * 300 / sampleRate)
    }

    /// Nächster Abtastwert in −peak…peak.
    public mutating func next() -> Float {
        if sound == .binaural { return nextBinaural() }
        let w = white()
        var x: Double
        switch sound {
        case .white:
            x = w * 0.11
        case .pink:
            x = pink(w) * 0.045
        case .brown:
            x = brown(w) * 1.1
        case .fan:
            x = fan(w)
        case .binaural, .rain, .ocean, .forest, .fire, .brook:
            // Einschlaf-Wellen: oben (zwei Kanäle). Aufnahmen werden nicht erzeugt (spielt `SoundPlayer`
            // aus der Datei).
            x = 0
        }
        // Gleichanteil entfernen, dann weich begrenzen (bleibt sicher unter der Spitze).
        let y = x - dcIn + dcPole * dcOut
        dcIn = x
        dcOut = y
        return Self.limit(y)
    }

    /// Weich begrenzen: bleibt sicher unter der Spitze, ohne harte Kanten.
    private static func limit(_ y: Double) -> Float { Float(peak * tanh(y / peak)) }

    /// Abwechselnd links und rechts. Sinus je ≈ −30 dBFS RMS, Rauschen ≈ −40 dBFS; ohne Gleichanteil
    /// (darum kein Filter, der sonst beide Kanäle vermischen würde).
    private mutating func nextBinaural() -> Float {
        if rightPending {
            rightPending = false
            return right
        }
        let bed = pink(white()) * 0.0058
        leftPhase += 200 / sampleRate
        if leftPhase >= 1 { leftPhase -= 1 }
        rightPhase += 202 / sampleRate
        if rightPhase >= 1 { rightPhase -= 1 }
        right = Self.limit(Self.binauralAmp * sin(2 * Double.pi * rightPhase) + bed)
        rightPending = true
        return Self.limit(Self.binauralAmp * sin(2 * Double.pi * leftPhase) + bed)
    }

    /// Füllt `count` Werte ab `buffer`.
    public mutating func fill(_ buffer: UnsafeMutablePointer<Float>, count: Int) {
        for i in 0..<count { buffer[i] = next() }
    }

    // MARK: Bausteine

    /// Gleichverteilt in −1…1 (SplitMix64).
    private mutating func white() -> Double {
        rng &+= 0x9E37_79B9_7F4A_7C15
        var z = rng
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        return Double(z >> 11) / Double(1 << 53) * 2 - 1
    }

    private mutating func pink(_ w: Double) -> Double {
        p0 = 0.99765 * p0 + w * 0.0990460
        p1 = 0.96300 * p1 + w * 0.2965164
        p2 = 0.57000 * p2 + w * 1.0526913
        return p0 + p1 + p2 + w * 0.1848
    }

    private mutating func brown(_ w: Double) -> Double {
        brownState = (brownState + 0.02 * w) / 1.02
        return brownState
    }

    /// Gleichmässig und sanft: gedämpftes tiefes Rauschen, das Brummen knapp hörbar (Phase durchgehend).
    private mutating func fan(_ w: Double) -> Double {
        fanLow = fanPole * fanLow + (1 - fanPole) * brown(w)
        humPhase += 50 / sampleRate
        if humPhase >= 1 { humPhase -= 1 }
        let hum = 0.006 * sin(2 * Double.pi * humPhase) + 0.003 * sin(4 * Double.pi * humPhase)
        return fanLow * 1.4 + hum
    }
}
