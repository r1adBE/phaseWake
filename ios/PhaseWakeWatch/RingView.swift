import PhaseWakeCore
import SwiftUI

/// Weckbildschirm: Weckpunkt gedrückt halten zum Stoppen, Schlummern mit Restanzahl.
struct RingView: View {
    @State private var progress: Double = 0
    @State private var holding = false
    private let alarm = SmartAlarm.shared

    var body: some View {
        ZStack {
            NightSky()
            Circle()
                .trim(from: 0, to: progress)
                .stroke(Brand.wave, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(3)
                .ignoresSafeArea()
            VStack(spacing: 4) {
                PulsingDot()
                    .frame(width: 70, height: 70)
                    .contentShape(Circle())
                    .onLongPressGesture(minimumDuration: 1.2, pressing: { pressing in
                        holding = pressing
                        withAnimation(pressing ? .linear(duration: 1.2) : .easeOut(duration: 0.25)) { progress = pressing ? 1 : 0 }
                    }, perform: { alarm.dismiss() })
                    .accessibilityElement()
                    .accessibilityLabel(L("ring_hold_to_stop"))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { alarm.dismiss() }
                content
            }
            .padding(.horizontal, 12)
        }
    }

    @ViewBuilder private var content: some View {
        switch alarm.phase {
        case .ringing(let reason, let snoozesLeft):
            Text(L("ring_greeting")).font(Fonts.manrope(18, .bold, relativeTo: .title3))
            Text(L(ringReasonKey(reason))).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.muted)
            Text(L("ring_hold_to_stop")).font(Fonts.manrope(11, relativeTo: .caption2)).foregroundStyle(Brand.muted)
            if snoozesLeft > 0 {
                Button(LP("ring_snooze", snoozesLeft, Int64(Snooze.minutes), Int64(snoozesLeft))) { alarm.snooze() }
                    .tint(Brand.deep)
            }
        case .snoozed(let until, _):
            Text(L("ring_snoozed_until", Format.time(until)))
                .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                .multilineTextAlignment(.center)
            // Beenden auch hier nur durch Halten (Punkt oben): ein Tipp im Halbschlaf beendet nicht die Nacht.
            Text(L("ring_hold_to_stop")).font(Fonts.manrope(11, relativeTo: .caption2)).foregroundStyle(Brand.muted)
        default:
            EmptyView()
        }
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
