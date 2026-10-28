import Foundation

/// Einschlafklänge, im Code erzeugt (keine Audiodateien). Identisch zu Kotlin.
public enum SleepSound: String, CaseIterable, Sendable {
    case white = "WHITE"
    case pink = "PINK"
    case brown = "BROWN"
    case rain = "RAIN"
    case ocean = "OCEAN"
}

/// Zeitplan der Einschlafklänge: Timer, Ein- und Ausblenden, spätestens Ende beim Weckfenster.
public enum SleepSounds {
    /// Timer-Auswahl in Minuten; `nil` = ohne Timer.
    public static let timerOptions: [Int?] = [15, 30, 60, nil]
    public static let defaultTimerMinutes = 30
    public static let fadeInSeconds = 3.0
    public static let fadeOutSeconds = 30.0

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

/// Rauschgenerator für die Einschlafklänge: deterministisch je Startwert, nahtlos (endloser Strom,
/// keine Schleife), ohne Gleichanteil und mit Spitzen unter −12 dBFS.
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
    // Regentropfen
    private var dropAmp = 0.0, dropTarget = 0.0, dropAttack = 0, dropAttackLeft = 0
    private var lastWhite = 0.0
    private let dropDecay: Double
    // Meer: langsame Welle
    private var swellPhase = 0.0, swellStep = 0.0

    public init(sound: SleepSound, sampleRate: Double = 48_000, seed: UInt64 = 1) {
        self.sound = sound
        self.sampleRate = sampleRate
        rng = seed &+ 0x9E37_79B9_7F4A_7C15
        // Hochpass um 5 Hz gegen jeden Gleichanteil.
        dcPole = exp(-2 * Double.pi * 5 / sampleRate)
        // Tropfen klingen in etwa 10 ms ab.
        dropDecay = exp(-1 / (0.010 * sampleRate))
        dropAttack = max(1, Int(0.002 * sampleRate))
        swellStep = 1 / (nextSwellPeriod() * sampleRate)
    }

    /// Nächster Abtastwert in −peak…peak.
    public mutating func next() -> Float {
        let w = white()
        var x: Double
        switch sound {
        case .white:
            x = w * 0.11
        case .pink:
            x = pink(w) * 0.045
        case .brown:
            x = brown(w) * 1.1
        case .rain:
            x = pink(w) * 0.032 + droplet(w)
        case .ocean:
            x = brown(w) * 1.3 * swell()
        }
        // Gleichanteil entfernen, dann weich begrenzen (bleibt sicher unter der Spitze).
        let y = x - dcIn + dcPole * dcOut
        dcIn = x
        dcOut = y
        return Float(Self.peak * tanh(y / Self.peak))
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

    private mutating func uniform() -> Double { (white() + 1) / 2 }

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

    /// Vereinzelte, weiche Tropfen: kurzer Anstieg (2 ms), dann Abklingen; im Mittel 4 je Sekunde.
    private mutating func droplet(_ w: Double) -> Double {
        if dropAttackLeft == 0 && dropAmp < 0.002 && uniform() < 4 / sampleRate {
            dropTarget = 0.04 + 0.08 * uniform()
            dropAttackLeft = dropAttack
        }
        if dropAttackLeft > 0 {
            dropAmp += dropTarget / Double(dropAttack)
            dropAttackLeft -= 1
        } else {
            dropAmp *= dropDecay
        }
        // Hell klingend: Differenz aufeinanderfolgender Zufallswerte.
        let bright = (w - lastWhite) * 0.5
        lastWhite = w
        return dropAmp * bright
    }

    /// Welle zwischen 0,35 und 1; jede Periode dauert zufällig 8–12 s und beginnt im Tal (ohne Sprung).
    private mutating func swell() -> Double {
        swellPhase += swellStep
        if swellPhase >= 1 {
            swellPhase -= 1
            swellStep = 1 / (nextSwellPeriod() * sampleRate)
        }
        return 0.35 + 0.65 * (0.5 - 0.5 * cos(2 * Double.pi * swellPhase))
    }

    private mutating func nextSwellPeriod() -> Double { 8 + 4 * uniform() }
}
