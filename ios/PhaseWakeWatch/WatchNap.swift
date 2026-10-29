import PhaseWakeCore
import SwiftUI

/// Powernap auf der Uhr: Dauer wählen (10–90 Min, Vorgabe 20) und gleich starten. Die Uhr misst in den
/// letzten Minuten wie in einer Nacht; die Sitzung lässt sich nur bei offener App planen, darum startet er hier.
struct WatchNapView: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var minutes = Transient.napDefaultMinutes

    var body: some View {
        List {
            Stepper(value: $minutes, in: Transient.napMinMinutes...Transient.napMaxMinutes, step: Transient.napStepMinutes) {
                Text(LP("nap_minutes", minutes, Int64(minutes)))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            }
            Button(L("nap_start")) {
                model.startNap(minutes: minutes)
                dismiss()
            }
            .tint(Brand.echo)
        }
        .navigationTitle(L("nap_title"))
    }
}

/// Zeile auf dem Startbildschirm: Powernap starten oder, solange er läuft, «Bis …» und Beenden.
struct WatchNapRow: View {
    @Environment(WatchModel.self) private var model
    @Binding var choosing: Bool

    var body: some View {
        if let nap = model.transient, nap.kind == .nap, model.now < nap.plan.target.addingTimeInterval(SmartAlarm.fallbackDelay) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("nap_running", Format.time(nap.plan.target))).font(Fonts.manrope(14, .semibold, relativeTo: .headline))
                Button(L("nap_stop")) { model.stopNap() }
                    .font(Fonts.manrope(13, relativeTo: .footnote))
            }
        } else if model.transient == nil {
            Button { choosing = true } label: {
                Label(L("nap_title"), systemImage: "powersleep")
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            }
        }
    }
}
