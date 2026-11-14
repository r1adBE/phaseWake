import PhaseWakeCore
import SwiftUI

/// Weckbildschirm: Weckpunkt oder Stopp-Knopf gedrückt halten zum Stoppen, Schlummern mit Restanzahl.
struct RingView: View {
    /// Mindesthöhe von «Halten zum Stoppen»: Tippziel nach Apple-Vorgabe (44 pt, Prüfrunde 2, B-16; vorher 40).
    static let stopMinHeight: CGFloat = 44
    @State private var progress: Double = 0
    @State private var holding = false
    private let alarm = SmartAlarm.shared

    var body: some View {
        ZStack {
            NightSky()
            // Fortschritt des Haltens zusätzlich als Ring am Rand.
            Circle()
                .trim(from: 0, to: progress)
                .stroke(Brand.wave, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(3)
                .ignoresSafeArea()
            // Kleiner Punkt und knappe Abstände (Audit U-1): «noch N» unter «Schlummern 5 Min» bleibt auch auf
            // kleinen Uhren ganz sichtbar. Mit grosser Systemschrift (Audit UI-3, wie Wear OS): Schrift auf diesem
            // Bildschirm gedeckelt, und reicht die Höhe nicht, fallen zuerst der Punkt, dann die Zeile mit dem Grund weg –
            // Stopp und Schlummern bleiben ganz sichtbar.
            ViewThatFits(in: .vertical) {
                column(dot: true, reason: true)
                column(dot: false, reason: true)
                column(dot: false, reason: false)
            }
            .dynamicTypeSize(...DynamicTypeSize.xLarge)
            .padding(.horizontal, 10)
        }
    }

    private func column(dot: Bool, reason: Bool) -> some View {
        VStack(spacing: 3) {
            if dot {
                PulsingDot()
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
                    .holdToStop(progress: $progress, holding: $holding) { alarm.dismiss() }
                    .accessibilityHidden(true)
            }
            content(reason: reason)
        }
    }

    @ViewBuilder private func content(reason showReason: Bool) -> some View {
        switch alarm.phase {
        case .ringing(let reason, let snoozesLeft):
            Text(L("ring_greeting")).font(Fonts.manrope(16, .bold, relativeTo: .headline)).lineLimit(1)
            if showReason { reasonText(L(ringReasonKey(reason))) }
            stopButton
            if snoozesLeft > 0 {
                // «Schlummern 5 Min» wie auf Wear OS (Audit F-10), darunter klein «noch N» (r1AD 10.11.2026); mindestens
                // 48 pt hoch als Tippziel, mit grosser Schrift höher. Zu lang bricht um statt abgeschnitten zu werden.
                Button { alarm.snooze() } label: {
                    VStack(spacing: 0) {
                        Text(L("ring_snooze_button", Int64(Snooze.minutes)))
                            .lineLimit(2)
                            .multilineTextAlignment(.center)
                        Text(LP("ring_snoozes_left", snoozesLeft, Int64(snoozesLeft)))
                            .font(Fonts.manrope(11, relativeTo: .caption2))
                            .foregroundStyle(Brand.muted)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, minHeight: 48)
                }
                .tint(Brand.deep)
            }
        case .snoozed(let until, _):
            Text(L("ring_snoozed_until", Format.time(until)))
                .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                .multilineTextAlignment(.center)
            // Beenden auch hier nur durch Halten: ein Tipp im Halbschlaf beendet nicht die Nacht.
            stopButton
        default:
            EmptyView()
        }
    }

    /// Grund beim Wecken ganz lesbar statt mit «…» (wie auf Wear OS): zuerst einzeilig, sonst etwas kleiner einzeilig,
    /// erst dann zweizeilig in voller Grösse.
    private func reasonText(_ text: String) -> some View {
        ViewThatFits(in: .horizontal) {
            Text(text).font(Fonts.manrope(12, relativeTo: .caption)).lineLimit(1)
            Text(text).font(Fonts.manrope(10.5, relativeTo: .caption)).lineLimit(1)
            Text(text).font(Fonts.manrope(12, relativeTo: .caption)).lineLimit(2).multilineTextAlignment(.center)
        }
        .foregroundStyle(Brand.muted)
    }

    /// «Halten zum Stoppen» als gefüllter Hauptknopf in Lavendel (Audit U-4), wie auf dem Handy und Wear OS: beim
    /// Halten läuft von links eine helle Füllung darüber. Dunkle Schrift auf beiden Farben: Kontrast ≥ 4.5:1.
    private var stopButton: some View {
        Text(L("ring_hold_to_stop"))
            .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
            .foregroundStyle(Brand.nightBottom)
            .multilineTextAlignment(.center)
            .lineLimit(2)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, minHeight: Self.stopMinHeight)
            .background {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Brand.wave
                        Brand.moon.frame(width: geo.size.width * progress)
                    }
                }
            }
            .clipShape(Capsule())
            .contentShape(Capsule())
            .holdToStop(progress: $progress, holding: $holding) { alarm.dismiss() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L("ring_hold_to_stop"))
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { alarm.dismiss() }
    }
}

private extension View {
    /// Halten (1,2 s) beendet; der Fortschritt füllt Knopf und Rand, loslassen vor dem Ende setzt zurück.
    func holdToStop(progress: Binding<Double>, holding: Binding<Bool>, perform: @escaping () -> Void) -> some View {
        onLongPressGesture(minimumDuration: 1.2, pressing: { pressing in
            holding.wrappedValue = pressing
            withAnimation(pressing ? .linear(duration: 1.2) : .easeOut(duration: 0.25)) { progress.wrappedValue = pressing ? 1 : 0 }
        }, perform: perform)
    }
}

/// Textschlüssel des Weckgrunds auf der Uhr, wie Wear OS («ring_reason_…»); auch für «Letzte Nacht».
func ringReasonKey(_ reason: WakeReason) -> String {
    switch reason {
    case .lightSleep: "ring_reason_light"
    case .awake: "ring_reason_awake"
    case .deadline: "ring_reason_deadline"
    case .exact: "ring_reason_exact"
    case .shortNight: "ring_reason_short_night"
    }
}
