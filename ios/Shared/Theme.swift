import CoreText
import PhaseWakeCore
import SwiftUI

/// Markenfarben aus dem Logo, identisch zu Android: Farbwelt «Mitternacht & Lavendel».
enum Brand {
    static let nightTop = Color(hex: 0x141A3D)
    static let nightBottom = Color(hex: 0x070914)
    static let glow = Color(hex: 0x7B6CF6)
    static let beam = Color(hex: 0xB9AEFF)
    static let wave = Color(hex: 0xB9AEFF)
    static let waveEcho = Color(hex: 0x7B6CF6)
    /// Mond, Weckpunkt und Text auf dem Nachthimmel: warmes Weiss.
    static let moon = Color(hex: 0xFBF7F0)
    /// Nebentext auf dem Nachthimmel, Kontrast ≥ 4.5:1 (WCAG AA).
    static let muted = Color(hex: 0xC9C3E6)
    static let deep = Color(hex: 0x3B3486)
    /// Knöpfe und Schalter auf der Uhr (mit warmem Weiss ≥ 4.5:1).
    static let echo = Color(hex: 0x4F45B8)
    static let track = Color(hex: 0x1E2347)
    static let accentLight = Color(hex: 0x3B3486)
    static let accentDark = Color(hex: 0xB9AEFF)
    /// Hintergrund hell: warmes Off-White.
    static let paper = Color(hex: 0xFBF7F0)

    /// Hauptfarbe je Modus: hell Indigo, dunkel Lavendel.
    static func accent(_ scheme: ColorScheme) -> Color { scheme == .dark ? accentDark : accentLight }
    /// Schrift auf der Hauptfarbe (Knopf «Neuer Wecker»).
    static func onAccent(_ scheme: ColorScheme) -> Color { scheme == .dark ? nightTop : .white }
    /// Karten: dunkel Weiss mit 6 % auf dem Nachthimmel, hell weiss.
    static func card(_ scheme: ColorScheme) -> Color { scheme == .dark ? .white.opacity(0.06) : .white }
    /// Rand eines nicht gewählten Chips, ≥ 3:1 zur Karte (WCAG 1.4.11): hell 90 % ≈ 3.4:1 auf Weiss,
    /// dunkel 75 % ≈ 4.7:1 auf der Karte über dem Nachthimmel (≈ 3.2:1 im Lavendel-Schein oben rechts).
    static func chipBorder(_ scheme: ColorScheme) -> Color { scheme == .dark ? wave.opacity(0.75) : glow.opacity(0.9) }
    /// Umriss von Bedienelementen (wie Material `outline` auf Android), ≥ 3:1 zu Karte und Hintergrund
    /// (WCAG 1.4.11): dunkel 726EA4 ≈ 3.5:1 auf der Karte, hell 8C87A8 ≈ 3.4:1 auf Weiss.
    static func outline(_ scheme: ColorScheme) -> Color { scheme == .dark ? Color(hex: 0x726EA4) : Color(hex: 0x8C87A8) }
    /// Fläche eines gewählten Chips.
    static func chipSelected(_ scheme: ColorScheme) -> Color { scheme == .dark ? wave.opacity(0.25) : Color(hex: 0xE4DFFB) }
    /// Leiser Nebentext (Erklärung), ≥ 4.5:1.
    static func secondaryText(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.muted : Color(hex: 0x5C5876) }
    /// Zusatzinfo, eine Stufe leiser: dunkel 80 %, hell ein fester Ton (80 % wäre auf Hell < 4.5:1).
    static func tertiaryText(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.muted.opacity(0.8) : Color(hex: 0x6A6684) }
    /// Kartenradius überall.
    static let cardRadius: CGFloat = 22
}

/// Farben der Schlafphasen in allen Diagrammen (Schlafprofil, Nachtkarte, Detailseite, Legenden), identisch zu Android.
/// Klar verschiedene Farbtöne und Helligkeiten, für Deuteranopie, Protanopie und Tritanopie simuliert (ΔE76 ≥ 24),
/// je ≥ 3:1 zur Karte (WCAG 1.4.11). Nie allein: Stufenhöhe und Beschriftung tragen die Information.
enum StageColors {
    /// Phasenfarbe: Wach Koralle, REM Türkis, Leicht Lavendel (Marke), Tief Indigo. Hell auf Weiss: CF4F3A 4.35:1,
    /// 0E8C9E 3.99:1, 7B6CF6 3.95:1, 2F2A85 11.7:1; dunkel auf der Karte: FF8F85 7.6:1, 6FF2E6 12.4:1, B9AEFF 8.4:1, 6F5CF0 3.6:1.
    static func color(_ stage: SleepStage, _ scheme: ColorScheme) -> Color {
        let dark = scheme == .dark
        switch stage {
        case .awake: return Color(hex: dark ? 0xFF8F85 : 0xCF4F3A)
        case .rem: return Color(hex: dark ? 0x6FF2E6 : 0x0E8C9E)
        case .light: return Color(hex: dark ? 0xB9AEFF : 0x7B6CF6)
        case .deep: return Color(hex: dark ? 0x6F5CF0 : 0x2F2A85)
        case .unknown: return track(scheme)
        }
    }

    /// Hintergrund der Zeilen im Schlafprofil (wie `chartTrack` auf Android).
    static func track(_ scheme: ColorScheme) -> Color { Color(hex: scheme == .dark ? 0x2A2D55 : 0xECE8F5) }
    /// Senkrechte Stufen zwischen den Phasen: Nebentext mit 60 %.
    static func link(_ scheme: ColorScheme) -> Color { Brand.secondaryText(scheme).opacity(0.6) }
    /// Weckpunkt und Zielzeit-Linie: die Hauptfarbe.
    static func marker(_ scheme: ColorScheme) -> Color { Brand.accent(scheme) }
    /// Weckfenster als Band: Hauptfarbe mit 12 %.
    static func window(_ scheme: ColorScheme) -> Color { Brand.accent(scheme).opacity(0.12) }
    /// «Üblicher Bereich» hinter den Summen: Nebentext mit 18 %, Rand gestrichelt in `typicalEdge`.
    static func typical(_ scheme: ColorScheme) -> Color { Brand.secondaryText(scheme).opacity(0.18) }
    /// Gestrichelter Rand des üblichen Bereichs.
    static func typicalEdge(_ scheme: ColorScheme) -> Color { Brand.secondaryText(scheme) }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}

/// Manrope (variable Schrift) aus dem App-Bündel registrieren; einmal beim Start.
enum Fonts {
    static func register() {
        guard let url = Bundle.main.url(forResource: "Manrope", withExtension: "ttf") else { return }
        CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
    }

    /// Manrope in Grösse und Stärke, wächst mit der dynamischen Schriftgrösse mit.
    static func manrope(_ size: CGFloat, _ weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        Font.custom("Manrope", size: size, relativeTo: style).weight(weight)
    }
}

/// Stimmung des Nachthimmels nach Tageszeit, wie `SkyMood` auf Android: `dusk` 0–1 färbt den Horizont am Abend leicht
/// rosé-violett, `dawn` 0–1 lässt ihn vor dem Weckfenster warm aufhellen. Nur unten am Horizont und schwach genug, dass
/// Schrift darauf ≥ 4.5:1 behält (auf Android mit SkyMoodTest geprüft, gleiche Werte).
struct SkyMood: Equatable {
    var dusk: Double = 0
    var dawn: Double = 0

    static let plain = SkyMood()
    /// Weckbildschirm ohne Sonnenaufgang: volle Morgendämmerung.
    static let wake = SkyMood(dawn: 1)
    /// Höchste Deckkraft ganz unten, Sonnenschein als Anteil davon, Beginn des Horizonts (Anteil der Höhe).
    static let maxAlpha = 0.20
    static let sunAlpha = 0.5
    static let horizonStart = 0.5

    /// Dämmerung in den drei Stunden vor dem Weckfenster und im Fenster voll, am frühen Morgen (5–10 Uhr) ohnehin;
    /// Abendrot von 17 bis 23 Uhr, am stärksten 19–21 Uhr.
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
        return SkyMood(dusk: evening * (1 - dawn), dawn: dawn)
    }

    private static func ramp(_ x: Double, _ from: Double, _ to: Double) -> Double {
        let f = min(1, max(0, (x - from) / (to - from)))
        return f * f * (3 - 2 * f)
    }

    /// Mischung aus Abendrot (9B5AA8) und Dämmerung (C86A5A) im sRGB-Raum und ihre Deckkraft ganz unten (0 = keine Färbung).
    var horizon: (color: Color, alpha: Double) {
        let total = min(1, dusk + dawn)
        guard total > 0 else { return (.clear, 0) }
        let w = dawn / (dusk + dawn)
        func mix(_ a: Double, _ b: Double) -> Double { (a + (b - a) * w) / 255 }
        return (Color(.sRGB, red: mix(0x9B, 0xC8), green: mix(0x5A, 0x6A), blue: mix(0xA8, 0x5A)), SkyMood.maxAlpha * total)
    }
}

/// Nachthimmel: Verlauf von Mitternachts-Indigo nach Fast-Schwarz, Lavendel-Schein oben rechts, unten der Horizont
/// nach `mood`. Auf dem iPhone wandert der Schein langsam (nicht mit «Bewegung reduzieren»); auf der Apple Watch ruht er
/// (Akku), und im Always-On (gedimmt) bleibt nur ein dunkler Grund ohne Schein.
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
                LinearGradient(colors: [Brand.nightTop.opacity(strength), Brand.nightBottom.opacity(strength), .black.opacity(1 - strength)], startPoint: .top, endPoint: .bottom)
                if !dimmed {
                    let horizon = mood.horizon
                    if horizon.alpha > 0 {
                        LinearGradient(
                            stops: [.init(color: horizon.color.opacity(0), location: SkyMood.horizonStart), .init(color: horizon.color.opacity(horizon.alpha), location: 1)],
                            startPoint: .top, endPoint: .bottom
                        )
                        RadialGradient(
                            colors: [horizon.color.opacity(horizon.alpha * SkyMood.sunAlpha), horizon.color.opacity(0)],
                            center: UnitPoint(x: 0.72, y: 1.04),
                            startRadius: 0,
                            endRadius: max(geo.size.width, geo.size.height) * 0.55
                        )
                    }
                    RadialGradient(
                        colors: [Brand.glow.opacity(0.55 * strength), Brand.glow.opacity(0.12 * strength), .clear],
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

/// Das Logo als Zeichnung: Mondsichel, Welle, Weckpunkt (200er Raster wie docs/logo.svg).
struct LogoMark: View {
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height) / 200
            context.scaleBy(x: s, y: s)
            var moon = Path(ellipseIn: CGRect(x: 34, y: 32, width: 80, height: 80))
            moon = moon.subtracting(Path(ellipseIn: CGRect(x: 53, y: 21, width: 76, height: 76)))
            context.fill(moon, with: .color(Brand.moon))
            var wave = Path()
            wave.move(to: CGPoint(x: 16, y: 158))
            wave.addCurve(to: CGPoint(x: 44, y: 122), control1: CGPoint(x: 30, y: 158), control2: CGPoint(x: 30, y: 122))
            wave.addCurve(to: CGPoint(x: 72, y: 158), control1: CGPoint(x: 58, y: 122), control2: CGPoint(x: 58, y: 158))
            wave.addCurve(to: CGPoint(x: 100, y: 118), control1: CGPoint(x: 86, y: 158), control2: CGPoint(x: 86, y: 118))
            wave.addCurve(to: CGPoint(x: 128, y: 152), control1: CGPoint(x: 114, y: 118), control2: CGPoint(x: 114, y: 152))
            wave.addCurve(to: CGPoint(x: 158, y: 122), control1: CGPoint(x: 140, y: 152), control2: CGPoint(x: 146, y: 134))
            context.stroke(wave, with: .color(Brand.wave), style: StrokeStyle(lineWidth: 9, lineCap: .round))
            context.fill(Path(ellipseIn: CGRect(x: 156, y: 108, width: 16, height: 16)), with: .color(Brand.moon))
        }
        .accessibilityHidden(true)
    }
}

/// Pulsierende Ringe um den Weckpunkt (Logo-Motiv). Auf dem iPhone fortlaufend; auf der Apple Watch nur ein paar Mal
/// beim Erscheinen, dann ruhend (Akku, wie Wear OS); mit «Bewegung reduzieren» und im Always-On gleich ruhend.
struct PulsingDot: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    #if os(watchOS)
    @Environment(\.isLuminanceReduced) private var dimmed
    private let pulses: Double? = 3
    #else
    private let dimmed = false
    private let pulses: Double? = nil
    #endif
    @State private var appeared = Date()
    /// Uhr: Pulse vorbei, die Zeitleiste hält an (kein Neuzeichnen mehr).
    @State private var done = false

    var body: some View {
        TimelineView(.animation(paused: reduceMotion || dimmed || done)) { timeline in
            Canvas { context, size in
                let elapsed = timeline.date.timeIntervalSince(appeared) / 2
                let still = reduceMotion || dimmed || done || (pulses.map { elapsed >= $0 } ?? false)
                let t = still ? 0.2 : elapsed.truncatingRemainder(dividingBy: 1)
                let base = min(size.width, size.height) / 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                for k in 0..<3 {
                    let p = (t + Double(k) / 3).truncatingRemainder(dividingBy: 1)
                    let r = base * (0.22 + 0.78 * p)
                    context.stroke(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r)), with: .color(Brand.wave.opacity((1 - p) * 0.7)), lineWidth: 2)
                }
                let d = base * 0.2
                context.fill(Path(ellipseIn: CGRect(x: center.x - d, y: center.y - d, width: 2 * d, height: 2 * d)), with: .color(Brand.moon))
            }
        }
        .onAppear { appeared = Date() }
        .task {
            guard let pulses else { return }
            try? await Task.sleep(for: .seconds(pulses * 2))
            done = true
        }
        .accessibilityHidden(true)
    }
}

/// Einheitliche Darstellung von Zeiten und Weckern.
enum Format {
    static func time(_ date: Date) -> String { date.formatted(date: .omitted, time: .shortened) }

    /// Uhrzeit in `zone` (Nacht aus der Gesundheits-App: Zone der Aufzeichnung, Audit F-3); `nil` = Zone des Geräts.
    static func time(_ date: Date, in zone: TimeZone?) -> String {
        var style = Date.FormatStyle(date: .omitted, time: .shortened)
        if let zone { style.timeZone = zone }
        return date.formatted(style)
    }

    /// Datum (abgekürzt, ohne Uhrzeit) in `zone`; `nil` = Zone des Geräts.
    static func day(_ date: Date, in zone: TimeZone?) -> String {
        var style = Date.FormatStyle(date: .abbreviated, time: .omitted)
        if let zone { style.timeZone = zone }
        return date.formatted(style)
    }

    static func time(hour: Int, minute: Int) -> String {
        let date = Calendar.current.date(from: DateComponents(hour: hour, minute: minute)) ?? Date()
        return time(date)
    }

    /// Uhrzeit für das Diagnose-Protokoll: immer «HH:mm» mit 0–9 in Ortszeit, unabhängig von Sprache und 12/24-Std.
    /// (wie Android; das Protokoll ist eine Ausgabe zum Auswerten, nicht zum Lesen in der App).
    static func logTime(_ date: Date, zone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", locale: Locale(identifier: "en_US_POSIX"), parts.hour ?? 0, parts.minute ?? 0)
    }

    /// Zweistellige Zahl («07») mit den Ziffern der Sprache wie bei den Uhrzeiten (Arabisch: ٠–٩, Audit UI-9);
    /// `String(format: "%02d")` gäbe immer 0–9.
    static func twoDigits(_ value: Int, locale: Locale = .current) -> String {
        value.formatted(.number.precision(.integerLength(2)).grouping(.never).locale(locale))
    }

    /// «06:30 – 07:00»; bei «Genau» (Fenster 0) «Genau um 07:00».
    static func window(_ alarm: AlarmSpec) -> String {
        let target = Calendar.current.date(from: DateComponents(hour: alarm.hour, minute: alarm.minute)) ?? Date()
        return window(start: target.addingTimeInterval(-Double(alarm.windowMinutes * 60)), target: target, exact: alarm.isExact)
    }

    /// «06:30 – 07:00» (Trenner wählbar, auf der Uhr ohne Leerzeichen); bei `exact` «Genau um 07:00»,
    /// denn ein genauer Wecker hat kein Weckfenster (die Uhr misst nur für den Verlauf).
    static func window(start: Date, target: Date, exact: Bool, separator: String = " – ") -> String {
        exact ? L("window_exact_line", time(target)) : time(start) + separator + time(target)
    }

    static func days(_ alarm: AlarmSpec) -> String {
        DayFormat.format(alarm.days, locale: .current, once: L("alarm_once"), daily: L("alarm_every_day"))
    }

    /// «Heute», «Morgen» oder der Wochentag («Montag») – nie «Morgen» für einen Tag weiter weg.
    static func day(_ date: Date, todayKey: String, tomorrowKey: String) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return L(todayKey) }
        if calendar.isDateInTomorrow(date) { return L(tomorrowKey) }
        return date.formatted(.dateTime.weekday(.wide)).capitalized(with: .current)
    }

    /// Kurzer Tag mit Datum ohne Jahr («Do., 30.10.») zu «yyyy-MM-dd», für «Übersprungen» und Feiertage.
    static func shortDay(_ date: String) -> String {
        let parts = date.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3,
              let day = Calendar.gregorianLocal.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12)) else { return date }
        return day.formatted(.dateTime.weekday(.abbreviated).day().month(.defaultDigits))
    }

    /// `%lld` braucht 64 Bit; `Int` hat auf arm64_32-Uhren nur 32, daher `Int64`.
    static func duration(minutes: Int) -> String {
        let m = Int64(max(0, minutes))
        if m < 60 { return L("duration_minutes", m) }
        if m % 60 == 0 { return L("duration_hours", m / 60) }
        if m % 30 == 0 { return L("duration_hours_half", m / 60) }
        return L("duration_hours_minutes", m / 60, m % 60)
    }
}

/// Übersetzter Text aus dem String-Katalog; Schlüssel wie die Android-Ressourcen.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    return args.isEmpty ? format : String(format: format, locale: .current, arguments: args)
}
