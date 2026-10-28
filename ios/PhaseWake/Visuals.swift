import PhaseWakeCore
import SwiftUI

/// Welle der Schlafzyklen, die im Weckfenster ausläuft und im pulsierenden Weckpunkt endet.
struct HeroWave: View {
    var windowFraction: Double = 0.32
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(paused: reduceMotion)) { timeline in
            Canvas { context, size in
                let t = timeline.date.timeIntervalSinceReferenceDate
                let phase = (t / 7).truncatingRemainder(dividingBy: 1) * 2 * .pi
                let pulse = (t / 2.2).truncatingRemainder(dividingBy: 1)
                let windowLeft = size.width * (1 - windowFraction)
                context.fill(Path(roundedRect: CGRect(x: windowLeft, y: 0, width: size.width - windowLeft, height: size.height), cornerRadius: 14), with: .color(.white.opacity(0.08)))
                let dotX = size.width - 14
                func y(_ i: Int) -> CGFloat {
                    let f = Double(i) / 80
                    return size.height * 0.55 + size.height * 0.32 * (1 - 0.6 * f) * sin(phase + f * 4.6 * .pi)
                }
                var echo = Path(), wave = Path()
                for i in 0...80 {
                    let x = dotX * CGFloat(i) / 80
                    if i == 0 { echo.move(to: CGPoint(x: x + 6, y: y(i) + 8)); wave.move(to: CGPoint(x: x, y: y(i))) }
                    else { echo.addLine(to: CGPoint(x: x + 6, y: y(i) + 8)); wave.addLine(to: CGPoint(x: x, y: y(i))) }
                }
                context.stroke(echo, with: .color(Brand.waveEcho), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                context.stroke(wave, with: .color(Brand.wave), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                let dot = CGPoint(x: dotX, y: y(80))
                let r = 6 + 12 * pulse
                context.stroke(Path(ellipseIn: CGRect(x: dot.x - r, y: dot.y - r, width: 2 * r, height: 2 * r)), with: .color(Brand.wave.opacity((1 - pulse) * 0.6)), lineWidth: 1.5)
                context.fill(Path(ellipseIn: CGRect(x: dot.x - 5, y: dot.y - 5, width: 10, height: 10)), with: .color(.white))
            }
        }
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
            let quiet = scheme == .dark ? Brand.echo : Color(hex: 0xB7C5EE)
            let active = scheme == .dark ? Color(hex: 0x7DB3FF) : Brand.accentLight
            let track = scheme == .dark ? Color(hex: 0x24345A) : Color(hex: 0xE1E6F0)
            for (i, e) in epochs.enumerated() {
                let h: CGFloat = e.motion.map { 3 + (size.height - 13) * CGFloat($0 / maxMotion) } ?? 3
                let color = i < unmeasured ? track.opacity(0.35) : e.motion == nil ? track : (i >= epochs.count - 3 ? active : quiet)
                context.fill(Path(roundedRect: CGRect(x: CGFloat(i) * (w + gap), y: size.height - h, width: w, height: h), cornerRadius: w / 2), with: .color(color))
            }
            if !epochs.isEmpty {
                let x = CGFloat(epochs.count - 1) * (w + gap) + w / 2
                context.fill(Path(ellipseIn: CGRect(x: x - 4, y: 1, width: 8, height: 8)), with: .color(scheme == .dark ? .white : Brand.nightTop))
            }
        }
        .accessibilityHidden(true)
    }
}

/// Kurzfassung einer Nacht: «12 Min vor der Zielzeit · Leichter Schlaf · ohne Schlummern»;
/// zu spät geweckt: «3 Min nach der Zielzeit · …».
func nightSummary(_ night: NightRecord) -> String {
    let reason: String = switch night.reason {
    case .lightSleep: L("reason_light")
    case .awake: L("reason_awake")
    case .deadline: L("reason_deadline")
    }
    let timing: String = if night.minutesBeforeTarget > 0 {
        L("woke_early", night.minutesBeforeTarget, reason)
    } else if night.minutesAfterTarget > 0 {
        L("woke_late_apple", night.minutesAfterTarget, reason)
    } else {
        L("woke_on_time", reason)
    }
    let snooze = night.snoozes == 0 ? L("no_snooze") : L("snoozed_times", night.snoozes)
    return "\(timing) · \(snooze)"
}
