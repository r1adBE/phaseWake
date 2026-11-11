import PhaseWakeCore
import SwiftUI
import UIKit

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
    /// Fläche des Himmels und der Texte (global), für die Schrift je Stelle (``SunriseSky/ink(_:area:aspect:)``).
    @State private var sky: CGRect = .zero
    @State private var areas: [SkyText: CGRect] = [:]
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let progress = progress(at: context.date)
            ZStack {
                if let progress { SunriseBackground(progress: progress) } else { Color.black.ignoresSafeArea() }
                Color.clear.ignoresSafeArea()
                    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { sky = $0 }
                VStack(spacing: 8) {
                    Spacer()
                    Text(Format.time(context.date))
                        .font(Fonts.manrope(64, .bold, relativeTo: .largeTitle))
                        .onSky(.time, ink: ink(.time, progress), night: Color.white.opacity(0.25), areas: $areas)
                    Text(subtitle(progress))
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                        .onSky(.subtitle, ink: ink(.subtitle, progress), muted: true, night: Color.white.opacity(0.2), areas: $areas)
                    Spacer()
                    Button(L("nightstand_end")) { dismiss() }
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                        .onSky(.end, ink: ink(.end, progress), night: Color.white.opacity(0.35), areas: $areas)
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

    /// Schrift für den Text `text` auf dem Sonnenaufgang `progress` (`nil` = Nachthimmel, dann keine).
    private func ink(_ text: SkyText, _ progress: Double?) -> SunriseSky.Ink? {
        guard let progress else { return nil }
        let aspect = sky.width > 0 ? sky.height / sky.width : SunriseSky.portrait
        let area = areas[text].map { SunriseSky.Area.of($0, in: sky) } ?? .center
        return SunriseSky.ink(progress, area: area, aspect: aspect)
    }

    /// Nächste Nacht mit Sonnenaufgang wie der Plan der App (Vollaudit AP2-6): früh geweckte bzw. von der Uhr übernommene
    /// Nächte übersprungen (`doneUntil`), «Gute Nacht» der Uhr verlegt die Zielzeit vor (`WatchNight.apply`).
    private func updatePlan() {
        let next = model.alarms.filter { $0.sunrise != .off }
        let now = Date()
        let found = WatchNight.shared.apply(AlarmSchedule.earliestPlan(next, now: now, doneUntil: model.doneUntil, calendar: .gregorianLocal,
                                                                       holidays: model.holidays.dates), now: now)
        // Ins Diagnose-Protokoll nur ein neuer oder geänderter Plan (ohne Wecker-Kennung), wie Android.
        if let found, found != plan { DiagStore.shared.log(.scheduled, "phone sunrise \(Format.logTime(found.windowStart)) – \(Format.logTime(found.target))") }
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

/// Texte auf dem Himmel des Nachttisch-Modus, je mit eigener Schrift nach ihrer Lage.
enum SkyText: Hashable { case time, subtitle, end }

extension View {
    /// Schrift `ink` (Haupt- oder mit `muted` der leisere Ton) samt halbdurchsichtiger Fläche dahinter
    /// (``SunriseSky/plaque(for:)``); ohne Sonnenaufgang (`ink` = `nil`) nur die gedämpfte Farbe `night`. Misst dabei
    /// die Lage des Texts in `areas`.
    func onSky(_ text: SkyText, ink: SunriseSky.Ink?, muted: Bool = false, night: Color,
               areas: Binding<[SkyText: CGRect]>) -> some View {
        foregroundStyle(ink.map { (muted ? $0.muted : $0.content).color } ?? night)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .background(Capsule().fill(ink.map { SunriseSky.plaque(for: $0.content) } ?? Color.clear))
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { areas.wrappedValue[text] = $0 }
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
