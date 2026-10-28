import CoreText
import PhaseWakeCore
import SwiftUI

/// Markenfarben aus dem Logo, identisch zu Android.
enum Brand {
    static let nightTop = Color(hex: 0x0B2170)
    static let nightBottom = Color(hex: 0x050D2B)
    static let glow = Color(hex: 0x3A74FF)
    static let beam = Color(hex: 0x5B8DFF)
    static let wave = Color(hex: 0x8CBBFF)
    static let waveEcho = Color(hex: 0x3557C8)
    /// Nebentext auf dem Nachthimmel, Kontrast ≥ 4.5:1 (WCAG AA).
    static let muted = Color(hex: 0xCFDBFF)
    static let deep = Color(hex: 0x1E3A8A)
    static let echo = Color(hex: 0x2F4FB8)
    static let track = Color(hex: 0x15213D)
    static let accentLight = Color(hex: 0x2F4FB8)
    static let accentDark = Color(hex: 0x8FB4FF)
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

/// Nachthimmel: Verlauf von Tiefblau nach Fast-Schwarz, Lichtschein oben rechts, der langsam wandert.
struct NightSky: View {
    var strength: Double = 1
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drift = false

    var body: some View {
        GeometryReader { geo in
            ZStack {
                LinearGradient(colors: [Brand.nightTop.opacity(strength), Brand.nightBottom.opacity(strength), .black.opacity(1 - strength)], startPoint: .top, endPoint: .bottom)
                RadialGradient(
                    colors: [Brand.glow.opacity(0.55 * strength), Brand.glow.opacity(0.12 * strength), .clear],
                    center: UnitPoint(x: drift ? 0.86 : 0.78, y: drift ? 0.14 : 0.08),
                    startRadius: 0,
                    endRadius: max(geo.size.width, geo.size.height) * 0.7
                )
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 9).repeatForever(autoreverses: true)) { drift = true }
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
            context.fill(moon, with: .color(.white))
            var wave = Path()
            wave.move(to: CGPoint(x: 16, y: 158))
            wave.addCurve(to: CGPoint(x: 44, y: 122), control1: CGPoint(x: 30, y: 158), control2: CGPoint(x: 30, y: 122))
            wave.addCurve(to: CGPoint(x: 72, y: 158), control1: CGPoint(x: 58, y: 122), control2: CGPoint(x: 58, y: 158))
            wave.addCurve(to: CGPoint(x: 100, y: 118), control1: CGPoint(x: 86, y: 158), control2: CGPoint(x: 86, y: 118))
            wave.addCurve(to: CGPoint(x: 128, y: 152), control1: CGPoint(x: 114, y: 118), control2: CGPoint(x: 114, y: 152))
            wave.addCurve(to: CGPoint(x: 158, y: 122), control1: CGPoint(x: 140, y: 152), control2: CGPoint(x: 146, y: 134))
            context.stroke(wave, with: .color(Brand.wave), style: StrokeStyle(lineWidth: 9, lineCap: .round))
            context.fill(Path(ellipseIn: CGRect(x: 156, y: 108, width: 16, height: 16)), with: .color(.white))
        }
        .accessibilityHidden(true)
    }
}

/// Pulsierende Ringe um den Weckpunkt (Logo-Motiv).
struct PulsingDot: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            Canvas { context, size in
                let t = timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 2) / 2
                let base = min(size.width, size.height) / 2
                let center = CGPoint(x: size.width / 2, y: size.height / 2)
                for k in 0..<3 {
                    let p = (t + Double(k) / 3).truncatingRemainder(dividingBy: 1)
                    let r = base * (0.22 + 0.78 * p)
                    context.stroke(Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r)), with: .color(Brand.wave.opacity((1 - p) * 0.7)), lineWidth: 2)
                }
                let d = base * 0.2
                context.fill(Path(ellipseIn: CGRect(x: center.x - d, y: center.y - d, width: 2 * d, height: 2 * d)), with: .color(.white))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Einheitliche Darstellung von Zeiten und Weckern.
enum Format {
    static func time(_ date: Date) -> String { date.formatted(date: .omitted, time: .shortened) }

    static func time(hour: Int, minute: Int) -> String {
        let date = Calendar.current.date(from: DateComponents(hour: hour, minute: minute)) ?? Date()
        return time(date)
    }

    /// «06:30 – 07:00»
    static func window(_ alarm: AlarmSpec) -> String {
        let target = Calendar.current.date(from: DateComponents(hour: alarm.hour, minute: alarm.minute)) ?? Date()
        return time(target.addingTimeInterval(-Double(alarm.windowMinutes * 60))) + " – " + time(target)
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

    static func duration(minutes: Int) -> String {
        let m = max(0, minutes)
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
