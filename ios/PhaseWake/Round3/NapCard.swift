import PhaseWakeCore
import SwiftUI

/// Powernap vom iPhone: Dauer 10–90 Minuten (Vorgabe 20). Die Apple Watch misst und weckt; sie plant die
/// Sitzung nur bei offener App, darum bittet die Statuskarte danach, Phase Wake auf der Uhr zu öffnen.
struct NapCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var minutes = Transient.napDefaultMinutes
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { withAnimation { expanded.toggle() } } label: {
                HStack {
                    Label(L("nap_title"), systemImage: "powersleep").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    Spacer()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down").accessibilityHidden(true)
                }
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isButton)
            if expanded {
                Stepper(value: $minutes, in: Transient.napMinMinutes...Transient.napMaxMinutes, step: Transient.napStepMinutes) {
                    Text(LP("nap_minutes", minutes, Int64(minutes))).font(Fonts.manrope(15, relativeTo: .body))
                }
                Text(L("nap_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
                Button(L("nap_start")) {
                    expanded = false
                    Task { await model.transient.startNap(minutes: minutes) }
                }
                .buttonStyle(.borderedProminent)
                .tint(Brand.accent(scheme))
                .foregroundStyle(Brand.onAccent(scheme))
            }
        }
        .card()
    }
}
