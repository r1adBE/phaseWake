import PhaseWakeCore
import SwiftUI

/// Knopf «In 2 Minuten testen» mit Erklärung (Nacht-Check-Karte, Einstellungen, Einrichtung).
struct TestAlarmButton: View {
    @Environment(PhoneModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { Task { await model.transient.startTest() } } label: {
                Label(L("test_button"), systemImage: "alarm.waves.left.and.right")
            }
            .disabled(model.transient.run != nil)
            Text(L("test_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
        }
    }
}

/// Karte, solange ein Probewecker oder Powernap läuft: Zeit, Hinweis auf die Uhr-App, beim Powernap «Beenden».
struct TransientStatusCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let transient = model.transient
        if transient.test != nil || transient.nap != nil {
            VStack(alignment: .leading, spacing: 6) {
                if let test = transient.test, !transient.showResult {
                    Label(L("test_running", Format.time(test.plan.target)), systemImage: "alarm")
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                }
                if let nap = transient.nap {
                    Label(L("nap_running", Format.time(nap.plan.target)), systemImage: "powersleep")
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    Button(L("nap_stop")) { Task { await transient.cancelNap() } }
                        .font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
                }
                if transient.waitingForWatch {
                    Label(L("watch_open_app"), systemImage: "applewatch")
                        .font(Fonts.manrope(14, relativeTo: .subheadline))
                        .foregroundStyle(Brand.secondaryText(scheme))
                }
            }
            .card()
        }
    }
}

/// Ergebnis des Probeweckers: je Schritt ✓/✗ mit Text (Farbe nie allein); nicht erfüllte Schritte mit dem
/// passenden Punkt des Nacht-Checks und, wo es geht, dem Knopf dazu.
struct TestResultSheet: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let run: TestRun

    var body: some View {
        NavigationStack {
            List {
                if TestStep.allCases.allSatisfy({ TestRuns.result(run, $0) != false }) {
                    Label(L("test_all_ok"), systemImage: "checkmark.seal").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                }
                ForEach(TestStep.allCases, id: \.self) { step in
                    row(step, TestRuns.result(run, step))
                }
            }
            .navigationTitle(L("test_result_title"))
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("test_close")) {
                        model.transient.closeResult()
                        dismiss()
                    }
                }
            }
        }
    }

    private func row(_ step: TestStep, _ ok: Bool?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 10) {
                Image(systemName: ok == nil ? "minus.circle" : ok == true ? "checkmark.circle.fill" : "xmark.circle")
                    .foregroundStyle(ok == false ? Color.red : Color.accentColor)
                    .accessibilityHidden(true)
                Text(title(step)).font(Fonts.manrope(15, .semibold, relativeTo: .body))
                Spacer()
                Text(L(ok == nil ? "test_step_skipped" : ok == true ? "test_step_passed" : "test_step_failed"))
                    .font(Fonts.manrope(13, relativeTo: .footnote))
            }
            .accessibilityElement(children: .combine)
            if ok == false, let check = Self.fix(step) {
                Text(check.problem).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(.secondary)
                if check.canFix {
                    Button(L("check_allow")) { Task { await NightCheck.fix(check) } }
                        .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                }
            }
        }
    }

    private func title(_ step: TestStep) -> String {
        switch step {
        case .received: L("test_step_received")
        case .watchRang: L("test_step_watch_rang")
        case .phoneRang: L("test_step_phone_rang")
        case .stopArrived: L("test_step_stop")
        }
    }

    /// Passender Punkt des Nacht-Checks: Uhr-App öffnen, scharf schalten, Wecker erlauben, Verbindung.
    static func fix(_ step: TestStep) -> PhoneCheck? {
        switch step {
        case .received: .watchApp
        case .watchRang: .watchArm
        case .phoneRang: .alarms
        case .stopArrived: .watch
        }
    }
}
