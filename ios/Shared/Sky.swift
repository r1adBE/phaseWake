import PhaseWakeCore
import SwiftUI

// Himmel der Apps: Nachthimmel nach Tageszeit (mit Sternen) und Weckhimmel, gleich wie Android und Wear OS.

/// Stimmung des Nachthimmels nach Tageszeit, wie `SkyMood` auf Android: `dusk` 0–1 färbt den Horizont am Abend
/// magenta-rosé mit violettem Schein, `dawn` 0–1 lässt ihn vor dem Weckfenster glutorange mit goldenem Schein aufgehen,
/// `night` 0–1 vertieft oben das Indigo und zeigt ruhende Sterne. So kräftig, wie Schrift darauf ≥ 4.5:1 behält (auf
/// Android mit SkyMoodTest geprüft, gleiche Werte).
struct SkyMood: Equatable {
    var dusk: Double = 0
    var dawn: Double = 0
    var night: Double = 0

    static let plain = SkyMood()
    static let duskLow: UInt32 = 0xC8308C, duskHigh: UInt32 = 0xB84CC8
    static let dawnLow: UInt32 = 0xE8581C, dawnHigh: UInt32 = 0xF0A830
    static let nightTop: UInt32 = 0x1C2366
    static let duskAlpha = 0.28, duskSun = 0.16, dawnAlpha = 0.26, dawnSun = 0.08
    /// Beginn des Horizonts (Anteil der Höhe); der Schein reicht nie darüber.
    static let horizonStart = 0.4
    /// Lichtschein oben rechts; in tiefer Nacht leiser.
    static let glowAlpha = 0.55, nightGlowDim = 0.15

    /// Dämmerung in den drei Stunden vor dem Weckfenster und im Fenster voll, am frühen Morgen (5–10 Uhr) ohnehin;
    /// Abendrot von 17 bis 23 Uhr, am stärksten 19–21 Uhr; tiefe Nacht ab 21:30 (voll ab 23 Uhr) bis gegen 5 Uhr.
    static func at(now: Date, plan: WakePlan?, calendar: Calendar = .current) -> SkyMood {
        var planDawn = 0.0
        if let plan {
            let minutes = plan.windowStart.timeIntervalSince(now) / 60
            if now >= plan.windowStart && now < plan.target { planDawn = 1 } else if minutes >= 0 { planDawn = min(1, max(0, 1 - minutes / 180)) }
        }
        let c = calendar.dateComponents([.hour, .minute], from: now)
        let hour = Double(c.hour ?? 0) + Double(c.minute ?? 0) / 60
        let dawn = max(planDawn, ramp(hour, 5, 7) * (1 - ramp(hour, 10, 11)))
        let evening = ramp(hour, 17, 19) * (1 - ramp(hour, 21, 23))
        let deep = hour >= 12 ? ramp(hour, 21.5, 23) : 1 - ramp(hour, 4, 5.5)
        return SkyMood(dusk: evening * (1 - dawn), dawn: dawn, night: deep * (1 - dawn))
    }

    private static func ramp(_ x: Double, _ from: Double, _ to: Double) -> Double {
        let f = min(1, max(0, (x - from) / (to - from)))
        return f * f * (3 - 2 * f)
    }

    /// Mischung zweier Farben (hex) im sRGB-Raum.
    static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> Color {
        func c(_ v: UInt32, _ shift: UInt32) -> Double { Double((v >> shift) & 0xFF) }
        func m(_ shift: UInt32) -> Double { (c(a, shift) + (c(b, shift) - c(a, shift)) * t) / 255 }
        return Color(.sRGB, red: m(16), green: m(8), blue: m(0))
    }

    private var tint: Double { min(1, dusk + dawn) }
    private var morningShare: Double { dusk + dawn > 0 ? dawn / (dusk + dawn) : 0 }
    /// Obere Farbe des Grundverlaufs: in tiefer Nacht kräftiges Indigo.
    var top: Color { SkyMood.mix(0x141A3D, SkyMood.nightTop, min(1, max(0, night))) }
    /// Horizontfarbe und ihre Deckkraft ganz unten (0 = keine Färbung).
    var horizon: (color: Color, alpha: Double) {
        guard tint > 0 else { return (.clear, 0) }
        let w = morningShare
        return (SkyMood.mix(SkyMood.duskLow, SkyMood.dawnLow, w), (SkyMood.duskAlpha + (SkyMood.dawnAlpha - SkyMood.duskAlpha) * w) * tint)
    }
    /// Schein über dem Horizont (violett am Abend, golden am Morgen) und seine Deckkraft in der Mitte.
    var sun: (color: Color, alpha: Double) {
        guard tint > 0 else { return (.clear, 0) }
        let w = morningShare
        return (SkyMood.mix(SkyMood.duskHigh, SkyMood.dawnHigh, w), (SkyMood.duskSun + (SkyMood.dawnSun - SkyMood.duskSun) * w) * tint)
    }
    /// Lichtschein oben rechts: Lavendel, am Abend zum Violett hin; Deckkraft in seiner Mitte.
    var glow: (color: Color, alpha: Double) {
        (SkyMood.mix(0x7B6CF6, SkyMood.duskHigh, min(1, max(0, dusk))), SkyMood.glowAlpha - SkyMood.nightGlowDim * min(1, max(0, night)))
    }
}

/// Nachthimmel: Verlauf von Mitternachts-Indigo nach Fast-Schwarz, Lavendel-Schein oben rechts, unten der Horizont
/// nach `mood`, in tiefer Nacht ruhende Sterne. Auf dem iPhone wandert der Schein langsam (nicht mit «Bewegung
/// reduzieren»); auf der Apple Watch ruht er (Akku), und im Always-On (gedimmt) bleibt nur ein dunkler Grund ohne Schein.
struct NightSky: View {
    var strength: Double = 1
    var mood: SkyMood = .plain
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    #if os(watchOS)
    @Environment(\.isLuminanceReduced) private var dimmed
    #else
    private let dimmed = false
    #endif
    @State private var drift = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                LinearGradient(colors: [mood.top.opacity(strength), Brand.nightBottom.opacity(strength), .black.opacity(1 - strength)], startPoint: .top, endPoint: .bottom)
                if !dimmed {
                    if mood.night > 0 { Stars().opacity(min(1, mood.night)) }
                    let horizon = mood.horizon
                    if horizon.alpha > 0 {
                        LinearGradient(
                            stops: [.init(color: horizon.color.opacity(0), location: SkyMood.horizonStart), .init(color: horizon.color.opacity(horizon.alpha), location: 1)],
                            startPoint: .top, endPoint: .bottom
                        )
                        let sun = mood.sun
                        RadialGradient(
                            colors: [sun.color.opacity(sun.alpha), sun.color.opacity(sun.alpha * 0.45), sun.color.opacity(0)],
                            center: UnitPoint(x: 0.5, y: 1),
                            startRadius: 0,
                            endRadius: geo.size.height * (1 - SkyMood.horizonStart)
                        )
                    }
                    let glow = mood.glow
                    RadialGradient(
                        colors: [glow.color.opacity(glow.alpha * strength), glow.color.opacity(0.12 * strength), .clear],
                        center: UnitPoint(x: drift ? 0.86 : 0.78, y: drift ? 0.14 : 0.08),
                        startRadius: 0,
                        endRadius: max(geo.size.width, geo.size.height) * 0.7
                    )
                }
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 1.5), value: mood)
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .onAppear {
            #if os(iOS)
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { drift = true }
            #endif
        }
    }
}

/// Sterne der tiefen Nacht wie Android: feste Lage aus demselben Zufallsstrom, klein und gedämpft, nur in der oberen
/// Hälfte. Ruhend.
struct Stars: View {
    static let points: [(x: Double, y: Double, size: Double)] = {
        var seed: Int64 = 0x5EED
        func next() -> Double {
            seed = (seed &* 1_103_515_245 &+ 12_345) & 0x7FFF_FFFF
            return Double(seed) / 2_147_483_647
        }
        return (0..<46).map { _ in (x: next(), y: next() * 0.5, size: next()) }
    }()

    var body: some View {
        Canvas { context, size in
            for p in Stars.points {
                let r = 0.6 + 0.9 * p.size
                let c = CGPoint(x: size.width * p.x, y: size.height * p.y)
                context.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)), with: .color(Brand.moon.opacity(0.35 + 0.45 * p.size)))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Himmel des Weckbildschirms, wie `WakeSky` auf Android und Wear OS: ein kräftiger Sonnenaufgang von Indigo über Violett
/// und Magenta-Rosé bis zur Glut unten, warmer Schein am unteren Rand, Lavendel-Schein oben rechts. Ruhend; helle Schrift
/// bleibt überall ≥ 4.5:1 (auf Android geprüft, gleiche Werte). Im Always-On nur ein dunkler Grund.
struct WakeSky: View {
    #if os(watchOS)
    @Environment(\.isLuminanceReduced) private var dimmed
    #else
    private let dimmed = false
    #endif

    var body: some View {
        GeometryReader { geo in
            if dimmed {
                Color.black
            } else {
                ZStack {
                    LinearGradient(stops: [
                        .init(color: Color(hex: 0x161A4A), location: 0),
                        .init(color: Color(hex: 0x2E1A5C), location: 0.35),
                        .init(color: Color(hex: 0x5E1E5E), location: 0.62),
                        .init(color: Color(hex: 0x82244A), location: 0.84),
                        .init(color: Color(hex: 0x8A2E0E), location: 1),
                    ], startPoint: .top, endPoint: .bottom)
                    RadialGradient(colors: [Color(hex: 0xC2481A, opacity: 0.12), Color(hex: 0xC2481A, opacity: 0)],
                                   center: UnitPoint(x: 0.5, y: 1), startRadius: 0, endRadius: geo.size.height * 0.5)
                    RadialGradient(colors: [Brand.glow.opacity(0.28), .clear],
                                   center: UnitPoint(x: 0.8, y: 0.1), startRadius: 0, endRadius: geo.size.height * 0.35)
                }
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
