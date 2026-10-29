import PhaseWakeCore
import SwiftUI
import WatchConnectivity

/// Versionszeile der Einstellungen: 7× tippen schaltet die Diagnose frei (ab 4× «Noch N Mal»).
struct DiagVersionRow: View {
    @Binding var unlocked: Bool
    @State private var taps = 0
    @State private var hint: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L("settings_version", DiagView.version))
            if let hint { Text(hint) }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .onTapGesture(perform: tap)
        .accessibilityAction { tap() }
    }

    private func tap() {
        guard !unlocked else { return }
        taps += 1
        if DiagUnlock.unlocked(taps) {
            DiagStore.shared.unlocked = true
            unlocked = true
            hint = L("diag_unlocked")
        } else if let left = DiagUnlock.remaining(taps) {
            hint = LP("diag_taps_left", left, Int64(left))
        }
    }
}

/// Diagnose am iPhone: Zustand von iPhone und Uhr, Ereignisse beider Geräte, Teilen, Uhr-Protokoll holen,
/// Leeren, Ausblenden. Nur lokal; geteilt wird nur, was der Nutzer selbst teilt.
struct DiagView: View {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "" }

    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Binding var unlocked: Bool
    @State private var entries: [DiagEntry] = []
    @State private var watchReport: DiagReport?
    @State private var noAnswer = false
    @State private var failed: [PhoneCheck] = []

    var body: some View {
        List {
            Text(L("diag_hint")).font(.footnote).foregroundStyle(.secondary)
            Section(L("diag_status")) {
                ForEach(status, id: \.label) { row in
                    LabeledContent(row.label, value: row.value)
                }
            }
            Section {
                ShareLink(item: shareText) { Label(L("diag_share"), systemImage: "square.and.arrow.up") }
                Button(L("diag_fetch_watch")) { Task { await fetchWatch() } }
                if noAnswer { Text(L("diag_watch_no_answer")).font(.footnote) }
                Button(L("diag_clear"), role: .destructive) {
                    DiagStore.shared.clear()
                    entries = []
                }
                Toggle(L("diag_show"), isOn: Binding(get: { unlocked }, set: { on in
                    DiagStore.shared.unlocked = on
                    unlocked = on
                    if !on { dismiss() }
                }))
            }
            Section(L("diag_events")) {
                if allEntries.isEmpty { Text(L("diag_empty")).foregroundStyle(.secondary) }
                ForEach(allEntries.indices.reversed(), id: \.self) { i in
                    Text(DiagLog.format([allEntries[i]], timeZone: .current)).font(.caption.monospaced())
                }
            }
        }
        .navigationTitle(L("diag_title"))
        .task {
            entries = DiagStore.shared.entries()
            failed = await NightCheck.collect()
        }
    }

    private var allEntries: [DiagEntry] {
        (entries + (watchReport?.entries ?? [])).sorted { $0.at < $1.at }
    }

    /// Zeilen «Bezeichnung: Wert» für iPhone und Uhr.
    private var status: [(label: String, value: String)] {
        let session = WCSession.isSupported() ? WCSession.default : nil
        let connected = session?.activationState == .activated && session?.isPaired == true && session?.isWatchAppInstalled == true
        let seen = UserDefaults.standard.double(forKey: PhoneConnectivity.watchSeenKey)
        let watch = Dictionary(watchReport?.status.map { ($0.key, $0.value) } ?? [], uniquingKeysWith: { $1 })
        let watchNext = watch["next"].flatMap { ISO8601DateFormatter().date(from: $0) }
        return [
            (L("diag_next_phone"), model.nextPlan().map { stamp($0.target) } ?? L("diag_none")),
            (L("diag_next_watch"), watchNext.map(stamp) ?? model.watchArmedTarget.map(stamp) ?? L("diag_unknown")),
            (L("check_alarmkit"), yesNo(!failed.contains(.alarms))),
            (L("check_notifications"), yesNo(!failed.contains(.notifications))),
            (L("diag_watch"), yesNo(connected)),
            (L("diag_watch_battery"), model.watchBattery().map { "\($0) %" } ?? L("diag_unknown")),
            (L("diag_watch_seen"), seen > 0 ? stamp(Date(timeIntervalSince1970: seen / 1000)) : L("diag_unknown")),
            (L("diag_versions"), L("diag_phone") + " " + Self.version + " · " + L("diag_watch") + " " + (watch["version"] ?? L("diag_unknown"))),
        ]
    }

    private var shareText: String {
        (status.map { "\($0.label): \($0.value)" } + ["", DiagLog.format(allEntries, timeZone: .current)]).joined(separator: "\n")
    }

    private func stamp(_ date: Date) -> String { date.formatted(date: .abbreviated, time: .shortened) }

    private func yesNo(_ value: Bool) -> String { L(value ? "diag_yes" : "diag_no") }

    private func fetchWatch() async {
        watchReport = await PhoneConnectivity.shared.requestWatchDiag()
        noAnswer = watchReport == nil
    }
}
