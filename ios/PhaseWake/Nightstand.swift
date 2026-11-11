import PhaseWakeCore
import SwiftUI
import UIKit

/// Farben eines Sonnenaufgangs, Verlauf identisch zu `SunriseSky` (Android).
///
/// Schrift (Audit UI-6): Für jeden Fortschritt die Schriftfarbe mit dem höheren Kontrast zur unteren Himmelsfarbe (dort
/// steigt die Sonne auf; der hellste Teil des Verlaufs), weiss oder fast schwarz – so liegt sie über den ganzen Verlauf
/// bei mindestens 4.5:1 (am Umschaltpunkt knapp 4.6:1; mit `Brand.nightBottom` wären es nur 4.45:1 gewesen). Die
/// gedämpfte Zeile nimmt ihren Farbton nur, solange er 4.5:1 erreicht, sonst die Schriftfarbe selbst.
enum SunriseSky {
    private struct Key { let at: Double; let top: UInt32; let bottom: UInt32; let sun: UInt32 }

    private static let keys = [
        Key(at: 0.00, top: 0x050208, bottom: 0x1E0604, sun: 0x4A1006),
        Key(at: 0.25, top: 0x14071A, bottom: 0x5A1508, sun: 0xA8320C),
        Key(at: 0.50, top: 0x3B1530, bottom: 0xC2410C, sun: 0xFF7A1A),
        Key(at: 0.75, top: 0xD9774A, bottom: 0xFFA25C, sun: 0xFFD08A),
        Key(at: 1.00, top: 0xFFE6C2, bottom: 0xFFF6E8, sun: 0xFFFFFF),
    ]

    /// Mindestkontrast der Schrift (WCAG AA).
    static let minContrast = 4.5

    /// Farbe als sRGB-Anteile 0–1.
    struct RGB: Equatable {
        let r: Double
        let g: Double
        let b: Double

        init(r: Double, g: Double, b: Double) {
            self.r = r
            self.g = g
            self.b = b
        }

        init(hex: UInt32) {
            self.init(r: Double((hex >> 16) & 0xFF) / 255, g: Double((hex >> 8) & 0xFF) / 255, b: Double(hex & 0xFF) / 255)
        }

        var color: Color { Color(red: r, green: g, blue: b) }
    }

    /// Weisse Schrift; dunkle Schrift (fast schwarz, Nachthimmel); gedämpfte Töne dazu.
    static let light = RGB(hex: 0xFFFFFF)
    static let dark = RGB(hex: 0x050208)
    private static let lightMuted = RGB(hex: 0xFFD9C2)
    private static let darkMuted = RGB(hex: 0x3A1E08)

    /// Alle Farben zu einem Fortschritt, als sRGB (testbar).
    struct Palette: Equatable { let top: RGB; let bottom: RGB; let sun: RGB; let content: RGB; let muted: RGB }

    struct Colors { let top: Color; let bottom: Color; let sun: Color; let content: Color; let muted: Color }

    static func palette(_ progress: Double) -> Palette {
        let p = min(1, max(0, progress))
        let i = min(keys.lastIndex { $0.at <= p } ?? 0, keys.count - 2)
        let a = keys[i], b = keys[i + 1]
        let t = min(1, max(0, (p - a.at) / (b.at - a.at)))
        let bottom = mix(a.bottom, b.bottom, t)
        let content = contrast(light, bottom) >= contrast(dark, bottom) ? light : dark
        let tinted = content == light ? lightMuted : darkMuted
        return Palette(top: mix(a.top, b.top, t), bottom: bottom, sun: mix(a.sun, b.sun, t), content: content,
                       muted: contrast(tinted, bottom) >= minContrast ? tinted : content)
    }

    static func colors(_ progress: Double) -> Colors {
        let c = palette(progress)
        return Colors(top: c.top.color, bottom: c.bottom.color, sun: c.sun.color, content: c.content.color, muted: c.muted.color)
    }

    /// Kontrastverhältnis nach WCAG 2 (1–21).
    static func contrast(_ x: RGB, _ y: RGB) -> Double {
        let lx = luminance(x), ly = luminance(y)
        return (max(lx, ly) + 0.05) / (min(lx, ly) + 0.05)
    }

    /// Relative Leuchtdichte nach WCAG 2.
    static func luminance(_ c: RGB) -> Double {
        func linear(_ v: Double) -> Double { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
    }

    private static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> RGB {
        let x = RGB(hex: a), y = RGB(hex: b)
        return RGB(r: x.r + (y.r - x.r) * t, g: x.g + (y.g - x.g) * t, b: x.b + (y.b - x.b) * t)
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
    /// Licht läuft (für «phone sunrise start/end» im Diagnose-Protokoll, wie Android).
    @State private var lit = false
    @Environment(\.scenePhase) private var scenePhase

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
            .onChange(of: progress) { _, value in
                setBrightness(value.map(Sunrise.brightness) ?? 0)
                if value != nil, !lit {
                    lit = true
                    DiagStore.shared.log(.ring, "phone sunrise start")
                }
            }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear {
            updatePlan()
            savedBrightness = screen?.brightness ?? 0.5
            UIApplication.shared.isIdleTimerDisabled = true
            setBrightness(0)
        }
        .onDisappear {
            restoreScreen()
            if lit { DiagStore.shared.log(.stop, "phone sunrise end") }
            lit = false
        }
        .onChange(of: scenePhase) { _, phase in
            // App verlassen oder gesperrt: Helligkeit und Ruhezustand sofort zurück, nicht erst beim Schliessen.
            if phase == .active {
                UIApplication.shared.isIdleTimerDisabled = true
                // Zurück im Nachttisch-Modus: wieder abdunkeln (bzw. Licht des Sonnenaufgangs), sonst bliebe es hell.
                savedBrightness = screen?.brightness ?? savedBrightness
                setBrightness(progress(at: Date()).map(Sunrise.brightness) ?? 0)
            } else {
                restoreScreen()
            }
        }
        .task {
            // Nach Zielzeit und Schlummern den nächsten Plan nehmen.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                if let plan, Date() > plan.target.addingTimeInterval(30 * 60) { updatePlan() }
            }
        }
    }

    private func updatePlan() {
        let next = model.alarms.filter { $0.sunrise != .off }
        let found = AlarmSchedule.earliestPlan(next, now: Date(), calendar: .gregorianLocal, holidays: model.holidays.dates)
        // Ins Diagnose-Protokoll nur ein neuer oder geänderter Plan (ohne Wecker-Kennung), wie Android.
        if let found, found != plan { DiagStore.shared.log(.scheduled, "phone sunrise \(Format.time(found.windowStart)) – \(Format.time(found.target))") }
        plan = found
        sunrise = next.first { $0.id == plan?.alarmId }?.sunrise ?? .off
    }

    private func restoreScreen() {
        UIApplication.shared.isIdleTimerDisabled = false
        screen?.brightness = savedBrightness
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

/// Karte «Nachttisch-Modus starten» auf der Startseite.
struct NightstandButton: View {
    let action: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Label(L("nightstand_start"), systemImage: "sunrise.fill").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Text(L("nightstand_hint")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme)).multilineTextAlignment(.leading)
                // Ehrlich (Audit D3, r1AD 10.11.2026): Der Bildschirm bleibt (dunkel) an, sonst sperrt iOS und der Sonnenaufgang käme nie.
                Text(L("nightstand_screen_on_hint")).font(Fonts.manrope(14, .semibold, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme)).multilineTextAlignment(.leading)
            }
        }
        .buttonStyle(.plain)
        .card()
    }
}
