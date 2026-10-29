import PhaseWakeCore
import SwiftUI
import WatchKit

/// Zustand der Uhr für den Diagnose-Bericht ans iPhone. Von jedem Thread lesbar (die Anfrage kommt auf der
/// Warteschlange von WatchConnectivity), geschrieben auf dem Hauptthread bei jedem Neuplanen.
final class WatchDiagStatus: @unchecked Sendable {
    static let shared = WatchDiagStatus()
    private let lock = NSLock()
    private var status: [(key: String, value: String)] = []

    /// Letzter Stand: `version`, `next` (ISO-Zeit oder leer), `battery` (Prozent oder leer), `exact`, `notifications`.
    var current: [(key: String, value: String)] {
        lock.lock(); defer { lock.unlock() }
        return status
    }

    @MainActor
    func update(plan: WakePlan?, armed: Bool) {
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        let battery = device.batteryLevel >= 0 ? String(Int((device.batteryLevel * 100).rounded())) : ""
        let next = plan.map { ISO8601DateFormatter().string(from: $0.target) } ?? ""
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        // «exact»: Auf der Apple Watch gibt es kein Recht für exakte Wecker; gemeldet wird, ob die Sitzung scharf ist.
        let values: [(key: String, value: String)] = [
            (key: "version", value: version), (key: "next", value: next), (key: "battery", value: battery),
            (key: "exact", value: armed ? "yes" : "no"), (key: "notifications", value: ""),
        ]
        lock.lock(); defer { lock.unlock() }
        status = values
    }
}

/// Versionszeile am Ende der Liste: 7× tippen schaltet die Diagnose frei (ab 4× «Noch N Mal»).
struct WatchVersionRow: View {
    @State private var taps = 0
    @State private var hint: String?
    @Binding var unlocked: Bool

    var body: some View {
        VStack(spacing: 2) {
            Text(L("diag_version", Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))
                .font(Fonts.manrope(11, relativeTo: .caption2))
                .foregroundStyle(Brand.muted)
            if let hint {
                Text(hint).font(Fonts.manrope(11, relativeTo: .caption2)).foregroundStyle(Brand.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture(perform: tap)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { tap() }
    }

    private func tap() {
        guard !unlocked else { return }
        taps += 1
        if DiagUnlock.unlocked(taps) {
            DiagStore.shared.unlocked = true
            unlocked = true
            hint = nil
        } else if let left = DiagUnlock.remaining(taps) {
            hint = LP("diag_taps_left", left, Int64(left))
        }
    }
}

/// Diagnose auf der Uhr: nächster Wecker, Ereignisse, Leeren, Ausblenden. Nur lokal.
struct WatchDiagView: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Binding var unlocked: Bool
    @State private var entries: [DiagEntry] = []

    var body: some View {
        List {
            Text(L("diag_next", model.plan.map { Format.time($0.target) } ?? "–"))
                .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
            if entries.isEmpty {
                Text(L("diag_empty")).font(Fonts.manrope(12, relativeTo: .footnote)).foregroundStyle(Brand.muted)
            }
            ForEach(entries.indices.reversed(), id: \.self) { i in
                Text(DiagLog.format([entries[i]], timeZone: .current))
                    .font(Fonts.manrope(11, relativeTo: .caption2))
            }
            Button(L("diag_clear")) {
                DiagStore.shared.clear()
                entries = []
            }
            Button(L("diag_hide")) {
                DiagStore.shared.unlocked = false
                unlocked = false
                dismiss()
            }
        }
        .navigationTitle(L("diag_title"))
        .onAppear { entries = DiagStore.shared.entries() }
    }
}
