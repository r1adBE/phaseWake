import PhaseWakeCore
import SwiftUI

/// Welle der Schlafzyklen, die im Weckfenster ausläuft und im pulsierenden Weckpunkt endet. Das Weckfenster leuchtet
/// als weiches Band, die Welle wird zum Weckpunkt hin heller und zeichnet sich beim Erscheinen einmal von links her
/// (wie Android). Mit «Bewegung reduzieren» ruht sie ganz.
struct HeroWave: View {
    var windowFraction: Double = 0.32
    /// Steht im Kopf der Startseite: dunkel im Nachthimmel, hell im Tageshimmel (``SkyInk``), wie Android `onNight`.
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var appeared = Date()

    var body: some View {
        // Höchstens 30 Bilder/s, im Hintergrund still.
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || scenePhase != .active)) { timeline in
            Canvas { context, size in
                let ink = SkyInk.of(scheme)
                let t = timeline.date.timeIntervalSinceReferenceDate
                let phase = reduceMotion ? 0 : (t / 7).truncatingRemainder(dividingBy: 1) * 2 * .pi
                let pulse = reduceMotion ? 0.45 : (t / 2.2).truncatingRemainder(dividingBy: 1)
                let raw = reduceMotion ? 1 : min(1, max(0, timeline.date.timeIntervalSince(appeared) / 1.1))
                let reveal = 1 - pow(1 - raw, 3)
                if windowFraction > 0 {
                    let left = size.width * (1 - windowFraction)
                    let band = Path(roundedRect: CGRect(x: left, y: 0, width: size.width - left, height: size.height), cornerRadius: 14)
                    context.fill(band, with: .linearGradient(
                        Gradient(stops: [.init(color: ink.band.opacity(0), location: 0), .init(color: ink.band.opacity(0.16 * reveal), location: 0.5), .init(color: ink.band.opacity(0.064 * reveal), location: 1)]),
                        startPoint: CGPoint(x: left, y: 0), endPoint: CGPoint(x: left, y: size.height)))
                    var edge = Path()
                    edge.move(to: CGPoint(x: left, y: 0)); edge.addLine(to: CGPoint(x: left, y: size.height))
                    context.stroke(edge, with: .linearGradient(
                        Gradient(colors: [ink.band.opacity(0), ink.band.opacity(0.48 * reveal), ink.band.opacity(0)]),
                        startPoint: CGPoint(x: left, y: 0), endPoint: CGPoint(x: left, y: size.height)), lineWidth: 1)
                }
                let dotX = size.width - 14
                func y(_ i: Int) -> CGFloat {
                    let f = Double(i) / 80
                    return size.height * 0.55 + size.height * 0.32 * (1 - 0.6 * f) * sin(phase + f * 4.6 * .pi)
                }
                let shown = min(80, max(1, Int(80 * reveal)))
                var echo = Path(), wave = Path()
                for i in 0...shown {
                    let x = dotX * CGFloat(i) / 80
                    if i == 0 { echo.move(to: CGPoint(x: x + 6, y: y(i) + 8)); wave.move(to: CGPoint(x: x, y: y(i))) }
                    else { echo.addLine(to: CGPoint(x: x + 6, y: y(i) + 8)); wave.addLine(to: CGPoint(x: x, y: y(i))) }
                }
                context.stroke(echo, with: .color(ink.waveEcho.opacity(0.8)), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                context.stroke(wave, with: .linearGradient(
                    Gradient(stops: [.init(color: ink.wave.opacity(0.7), location: 0), .init(color: ink.wave, location: 0.55), .init(color: ink.moon, location: 1)]),
                    startPoint: .zero, endPoint: CGPoint(x: dotX, y: 0)), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                guard shown == 80 else { return }
                let dot = CGPoint(x: dotX, y: y(80))
                context.fill(Path(ellipseIn: CGRect(x: dot.x - 22, y: dot.y - 22, width: 44, height: 44)), with: .radialGradient(
                    Gradient(colors: [ink.wave.opacity(0.45), ink.wave.opacity(0)]), center: dot, startRadius: 0, endRadius: 22))
                let r = 6 + 12 * pulse
                context.stroke(Path(ellipseIn: CGRect(x: dot.x - r, y: dot.y - r, width: 2 * r, height: 2 * r)), with: .color(ink.wave.opacity((1 - pulse) * 0.6)), lineWidth: 1.5)
                context.fill(Path(ellipseIn: CGRect(x: dot.x - 5, y: dot.y - 5, width: 10, height: 10)), with: .color(ink.moon))
            }
        }
        .onAppear { appeared = Date() }
        .accessibilityHidden(true)
    }
}

/// Glaskante der Karten wie Android (`cardEdge`): dunkel oben hell mit Lavendel-Schimmer, nach unten auslaufend; hell
/// ein Hauch Lavendel. Reine Zierde.
struct CardEdge: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        RoundedRectangle(cornerRadius: Brand.cardRadius)
            .strokeBorder(scheme == .dark
                          ? AnyShapeStyle(LinearGradient(colors: [.white.opacity(0.30), Brand.wave.opacity(0.10), .white.opacity(0.02)], startPoint: .top, endPoint: .bottom))
                          : AnyShapeStyle(Brand.glow.opacity(0.10)), lineWidth: 1)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// Bewegungskurve eines Weckfensters als Balken; die letzten vor dem Wecken kräftig.
struct NightBars: View {
    let epochs: [Epoch]
    let totalEpochs: Int
    /// Ungemessene Epochen am Anfang (vor der Sitzung der Apple Watch): nur angedeutet, nicht als Lücke.
    var unmeasured = 0
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Canvas { context, size in
            let slots = max(1, max(totalEpochs, epochs.count))
            let gap: CGFloat = 2
            let w = max(1, (size.width - gap * CGFloat(slots - 1)) / CGFloat(slots))
            let maxMotion = max(0.01, epochs.compactMap(\.motion).max() ?? 0.01)
            // Wie `BrandColors` auf Android (chartQuiet, chartActive, chartTrack).
            let quiet = scheme == .dark ? Color(hex: 0x4A43A0) : Color(hex: 0xDAD4FA)
            let active = scheme == .dark ? Color(hex: 0xB9AEFF) : Color(hex: 0x7B6CF6)
            let track = scheme == .dark ? Color(hex: 0x2A2D55) : Color(hex: 0xF1E7D8)
            for (i, e) in epochs.enumerated() {
                let h: CGFloat = e.motion.map { 3 + (size.height - 13) * CGFloat($0 / maxMotion) } ?? 3
                let color = i < unmeasured ? track.opacity(0.35) : e.motion == nil ? track : (i >= epochs.count - 3 ? active : quiet)
                context.fill(Path(roundedRect: CGRect(x: CGFloat(i) * (w + gap), y: size.height - h, width: w, height: h), cornerRadius: w / 2), with: .color(color))
            }
            if !epochs.isEmpty {
                let x = CGFloat(epochs.count - 1) * (w + gap) + w / 2
                context.fill(Path(ellipseIn: CGRect(x: x - 4, y: 1, width: 8, height: 8)), with: .color(scheme == .dark ? Brand.moon : Brand.accentLight))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Weckgrund als Text, wie Android («reason_…»).
func reasonText(_ reason: WakeReason) -> String {
    switch reason {
    case .lightSleep: L("reason_light")
    case .awake: L("reason_awake")
    case .deadline: L("reason_deadline")
    case .exact: L("reason_exact")
    case .shortNight: L("reason_short_night")
    }
}

extension NightRecord {
    /// Nacht eines Weckers «Genau»: Es gab kein Weckfenster, nur Messen für den Verlauf (Anzeige ohne Fensterband).
    var wasExact: Bool { reason == .exact }
}

/// Zeitpunkt und Grund: «12 Min vor der Zielzeit · Leichter Schlaf»; zu spät geweckt: «3 Min nach der Zielzeit · …».
func wakeTiming(_ night: NightRecord) -> String {
    let reason = reasonText(night.reason)
    let timing: String = if night.minutesBeforeTarget > 0 {
        L("woke_early", night.minutesBeforeTarget, reason)
    } else if night.minutesAfterTarget > 0 {
        LP("woke_late", night.minutesAfterTarget, Int64(night.minutesAfterTarget), reason)
    } else {
        L("woke_on_time", reason)
    }
    return timing
}

/// Kurzfassung einer Nacht: «12 Min vor der Zielzeit · Leichter Schlaf · ohne Schlummern»;
/// zu spät geweckt: «3 Min nach der Zielzeit · …».
func nightSummary(_ night: NightRecord) -> String {
    let snooze = night.snoozes == 0 ? L("no_snooze") : L("snoozed_times", night.snoozes)
    return "\(wakeTiming(night)) · \(snooze)"
}
