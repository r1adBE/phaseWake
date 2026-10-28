import PhaseWakeCore
import SwiftUI
import UIKit

/// Farben eines Sonnenaufgangs, identisch zu `SunriseSky` (Android).
enum SunriseSky {
    private struct Key { let at: Double; let top: UInt32; let bottom: UInt32; let sun: UInt32 }

    private static let keys = [
        Key(at: 0.00, top: 0x050208, bottom: 0x1E0604, sun: 0x4A1006),
        Key(at: 0.25, top: 0x14071A, bottom: 0x5A1508, sun: 0xA8320C),
        Key(at: 0.50, top: 0x3B1530, bottom: 0xC2410C, sun: 0xFF7A1A),
        Key(at: 0.75, top: 0xD9774A, bottom: 0xFFA25C, sun: 0xFFD08A),
        Key(at: 1.00, top: 0xFFE6C2, bottom: 0xFFF6E8, sun: 0xFFFFFF),
    ]

    /// Ab hier ist dunkle Schrift kontrastreicher als weisse.
    static let darkContentFrom = 0.635

    struct Colors { let top: Color; let bottom: Color; let sun: Color; let content: Color; let muted: Color }

    static func colors(_ progress: Double) -> Colors {
        let p = min(1, max(0, progress))
        let i = min(keys.lastIndex { $0.at <= p } ?? 0, keys.count - 2)
        let a = keys[i], b = keys[i + 1]
        let t = min(1, max(0, (p - a.at) / (b.at - a.at)))
        let dark = p >= darkContentFrom
        return Colors(
            top: mix(a.top, b.top, t), bottom: mix(a.bottom, b.bottom, t), sun: mix(a.sun, b.sun, t),
            content: dark ? Brand.nightBottom : .white,
            muted: dark ? Color(hex: 0x3A1E08) : Color(hex: 0xFFD9C2)
        )
    }

    private static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> Color {
        func c(_ v: UInt32, _ shift: UInt32) -> Double { Double((v >> shift) & 0xFF) / 255 }
        return Color(
            red: c(a, 16) + (c(b, 16) - c(a, 16)) * t,
            green: c(a, 8) + (c(b, 8) - c(a, 8)) * t,
            blue: c(a, 0) + (c(b, 0) - c(a, 0)) * t
        )
    }
}

/// Himmel zum Fortschritt 0–1; die Sonne steigt von unten auf.
struct SunriseBackground: View {
    let progress: Double

    var body: some View {
        let c = SunriseSky.colors(progress)
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height
            let radius = w * (0.45 + 1.05 * progress)
            ZStack {
                LinearGradient(colors: [c.top, c.bottom], startPoint: .top, endPoint: .bottom)
                RadialGradient(
                    stops: [.init(color: c.sun, location: 0), .init(color: c.sun.opacity(0.55), location: 0.35), .init(color: c.sun.opacity(0), location: 1)],
                    center: UnitPoint(x: 0.5, y: 1.18 - 0.5 * progress),
                    startRadius: 0,
                    endRadius: radius
                )
                .frame(width: w, height: h)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}

/// Nachttisch-Modus: Phase Wake bleibt nachts offen; der Bildschirm ist schwarz und fast aus,
/// bis der Sonnenaufgang beginnt. Nur so kann das iPhone ein eigenes Licht zeigen.
struct NightstandView: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var plan: WakePlan?
    @State private var sunrise: Sunrise = .off
    @State private var savedBrightness: CGFloat = 0.5

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let progress = progress(at: context.date)
            ZStack {
                if let progress { SunriseBackground(progress: progress) } else { Color.black.ignoresSafeArea() }
                let colors = SunriseSky.colors(progress ?? 0)
                VStack(spacing: 8) {
                    Spacer()
                    Text(Format.time(context.date))
                        .font(Fonts.manrope(64, .bold, relativeTo: .largeTitle))
                        .foregroundStyle(progress == nil ? Color.white.opacity(0.25) : colors.content)
                    Text(subtitle(progress))
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                        .foregroundStyle(progress == nil ? Color.white.opacity(0.2) : colors.muted)
                    Spacer()
                    Button(L("nightstand_end")) { dismiss() }
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                        .foregroundStyle(progress == nil ? Color.white.opacity(0.35) : colors.content)
                        .padding(.bottom, 32)
                }
            }
            .onChange(of: progress) { _, value in setBrightness(value.map(Sunrise.brightness) ?? 0) }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            let next = model.alarms.filter { $0.sunrise != .off }
            plan = AlarmSchedule.earliestPlan(next, now: Date(), calendar: .current)
            sunrise = next.first { $0.id == plan?.alarmId }?.sunrise ?? .off
            savedBrightness = screen?.brightness ?? 0.5
            UIApplication.shared.isIdleTimerDisabled = true
            setBrightness(0)
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            screen?.brightness = savedBrightness
        }
    }

    private var screen: UIScreen? {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.screen
    }

    private func setBrightness(_ value: Double) { screen?.brightness = CGFloat(value) }

    private func subtitle(_ progress: Double?) -> String {
        guard let plan else { return L("nightstand_no_alarm") }
        if progress != nil { return L("sunrise_title") }
        return L("nightstand_waiting", Format.time(sunrise == .window ? plan.windowStart : plan.target))
    }

    /// Wie beim Android-Weckbildschirm: ab Fenster bis zur Zielzeit; weckt die Uhr früher, in einer
    /// Minute voll. «Beim Wecken»: ab dem Weckmoment der Uhr (spätestens Zielzeit) in zwei Minuten.
    private func progress(at now: Date) -> Double? {
        guard let plan, sunrise != .off else { return nil }
        let ring = model.lastRing.flatMap { $0 >= plan.windowStart && $0 <= plan.target.addingTimeInterval(600) ? $0 : nil }
        switch sunrise {
        case .window:
            guard now >= plan.windowStart else { return nil }
            var p = Sunrise.progress(start: plan.windowStart, end: plan.target, now: now)
            if let ring, now >= ring {
                let from = Sunrise.progress(start: plan.windowStart, end: plan.target, now: ring)
                p = max(p, from + (1 - from) * min(1, now.timeIntervalSince(ring) / Double(Sunrise.catchUpSeconds)))
            }
            return p
        case .ring:
            let start = ring ?? plan.target
            guard now >= start else { return nil }
            return min(1, now.timeIntervalSince(start) / Double(Sunrise.ringSeconds))
        case .off:
            return nil
        }
    }
}
