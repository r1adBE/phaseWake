import PhaseWakeCore
import SwiftUI

/// Nächster Wecker im Kopf der Startseite, wie Android (`WakeArc` und `Header`): grosser Countdown-Bogen, darin das
/// Weckfenster gross, darunter wann und welcher, dann wie lange noch (oder: Fenster läuft).
struct HeroPlan: View {
    let plan: WakePlan
    let alarm: AlarmSpec
    let now: Date
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let day = Format.day(plan.target, todayKey: "home_next_today", tomorrowKey: "home_next_tomorrow")
        // «Genau»: bis zur Zielzeit zählen; das Messen davor ist kein Weckfenster und weckt nie früher.
        let minutes = Int((plan.exact ? plan.target : plan.windowStart).timeIntervalSince(now) / 60)
        ZStack(alignment: .top) {
            WakeArc(now: now, windowStart: plan.windowStart, target: plan.target, exact: plan.exact)
                .aspectRatio(WakeArc.aspect, contentMode: .fit)
            VStack(spacing: 2) {
                Text(Format.window(start: plan.windowStart, target: plan.target, exact: plan.exact))
                    .font(Fonts.manrope(plan.exact ? 44 : 56, .heavy, relativeTo: .largeTitle))
                    .minimumScaleFactor(0.4)
                    .lineLimit(1)
                Text([day, alarm.label].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Text(plan.exact ? L("home_next_in_exact", Format.duration(minutes: max(0, minutes)))
                     : minutes > 0 ? L("home_next_in", Format.duration(minutes: minutes)) : L("home_window_running"))
                    .font(Fonts.manrope(14, relativeTo: .subheadline))
                    .foregroundStyle(SkyInk.of(scheme).muted)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, width * WakeArc.textSide)
            .padding(.top, width * WakeArc.textTop)
        }
        // Breite des Bogens, damit die Texte innerhalb der Bogenlinie stehen.
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .padding(.top, 6)
    }

    @State private var width: CGFloat = 340
}

/// Countdown zum nächsten Wecker als grosser Bogen (wie eine Sonnenbahn), wie `WakeArc` auf Android: Der Bogen steht
/// für die letzten 12 Stunden vor der Zielzeit; links füllt er sich bis «jetzt» (heller Punkt), rechts leuchtet das
/// Weckfenster als warmes Segment und endet im Weckpunkt. Zeichnet sich beim Erscheinen einmal ein (nicht mit «Bewegung
/// reduzieren»), danach ruht er. Reine Zierde.
struct WakeArc: View {
    let now: Date
    let windowStart: Date
    let target: Date
    let exact: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Farben des Himmels darunter: dunkel Nacht, hell Tag (``SkyInk``).
    @Environment(\.colorScheme) private var scheme
    @State private var reveal: Double = 0

    var body: some View {
        WakeArcDrawing(now: now, windowStart: windowStart, target: target, exact: exact, ink: SkyInk.of(scheme), reveal: reveal)
            .accessibilityHidden(true)
            .onAppear {
                guard !reduceMotion else { reveal = 1; return }
                withAnimation(.easeOut(duration: 1.1)) { reveal = 1 }
            }
    }

    static let aspect: CGFloat = 1.5
    static let textTop: CGFloat = 0.25
    static let textSide: CGFloat = 0.11
    static let hours: Double = 12
    static let windowShare: Double = 0.16

    /// Lage auf dem Bogen wie `arcPosition` (Android): das Weckfenster immer mit `windowShare`, die Zeit davor
    /// gleichmässig.
    static func position(_ at: Date, windowStart: Date, target: Date) -> Double {
        let window = target.timeIntervalSince(windowStart) / 60
        let share = window > 0 ? windowShare : 0
        if at >= windowStart {
            guard window > 0 else { return 1 }
            return min(1, max(0, 1 - share + share * (at.timeIntervalSince(windowStart) / 60) / window))
        }
        let lead = max(1, hours * 60 - window)
        let before = windowStart.timeIntervalSince(at) / 60
        return min(1 - share, max(0, (1 - share) * (1 - before / lead)))
    }
}

/// Zeichnung des Bogens; `reveal` (0–1) wird beim Erscheinen animiert (Animatable).
private struct WakeArcDrawing: View, Animatable {
    let now: Date
    let windowStart: Date
    let target: Date
    let exact: Bool
    let ink: SkyInk
    var reveal: Double
    var animatableData: Double {
        get { reveal }
        set { reveal = newValue }
    }
    private typealias A = WakeArc
    private static let start: Double = 165
    private static let sweep: Double = 210

    var body: some View {
        Canvas { context, size in
            let stroke: CGFloat = 9
            let pad = stroke * 1.6
            let r = size.width / 2 - pad
            let center = CGPoint(x: size.width / 2, y: pad + r)
            let begin = exact ? target : windowStart
            let nowAt = A.position(now, windowStart: begin, target: target)
            let windowAt = A.position(begin, windowStart: begin, target: target)
            func angle(_ p: Double) -> Angle { .degrees(Self.start + Self.sweep * p) }
            func at(_ p: Double, _ radius: CGFloat) -> CGPoint {
                let a = angle(p).radians
                return CGPoint(x: center.x + radius * cos(a), y: center.y + radius * sin(a))
            }
            func arc(_ from: Double, _ to: Double) -> Path {
                Path { $0.addArc(center: center, radius: r, startAngle: angle(from), endAngle: angle(to), clockwise: false) }
            }
            func dot(_ c: CGPoint, _ radius: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: 2 * radius, height: 2 * radius)) }
            func gradientArc(_ from: Double, _ to: Double, _ a: Color, _ b: Color, _ width: CGFloat) {
                guard to > from else { return }
                context.stroke(arc(from, to), with: .linearGradient(Gradient(colors: [a, b]), startPoint: at(from, r), endPoint: at(to, r)),
                               style: StrokeStyle(lineWidth: width, lineCap: .round))
            }
            context.stroke(arc(0, 1), with: .color(ink.text.opacity(0.10)), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
            for k in 0...12 {
                context.fill(dot(at(Double(k) / 12, r - stroke * 1.35), k % 3 == 0 ? 1.6 : 1.1), with: .color(ink.muted.opacity(k % 3 == 0 ? 0.55 : 0.28)))
            }
            let shown = reveal
            gradientArc(0, min(nowAt, windowAt) * shown, ink.waveEcho.opacity(0.55), ink.wave, stroke)
            if !exact && shown > windowAt {
                context.stroke(arc(windowAt, shown), with: .color(ink.dawn.opacity(0.18)), style: StrokeStyle(lineWidth: stroke * 2.6, lineCap: .round))
                gradientArc(windowAt, shown, ink.wave, ink.dawn, stroke * 1.15)
            }
            guard shown >= 1 else { return }
            let wake = at(1, r)
            context.fill(dot(wake, stroke * 2.6), with: .radialGradient(Gradient(colors: [ink.dawn.opacity(0.55), ink.dawn.opacity(0)]), center: wake, startRadius: 0, endRadius: stroke * 2.6))
            context.stroke(dot(wake, stroke * 1.05), with: .color(ink.moon.opacity(0.7)), lineWidth: 1.5)
            context.fill(dot(wake, stroke * 0.62), with: .color(ink.moon))
            if now < target {
                let p = at(nowAt, r)
                context.fill(dot(p, stroke * 1.9), with: .radialGradient(Gradient(colors: [ink.wave.opacity(0.6), ink.wave.opacity(0)]), center: p, startRadius: 0, endRadius: stroke * 1.9))
                context.fill(dot(p, stroke * 0.5), with: .color(ink.moon))
            }
        }
    }
}
