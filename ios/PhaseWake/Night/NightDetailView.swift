import PhaseWakeCore
import SwiftUI

/// Detailseite einer Nacht, aus dem Verlauf geöffnet (Nachtkarte oder Blase eines Diagramms). Seitlich wischen
/// blättert zur vorherigen bzw. nächsten Nacht; die Knöpfe oben tun dasselbe (auch für VoiceOver und ohne Wischen).
/// `items` neueste zuerst wie der Verlauf. Wie `NightDetailScreen` (Android).
struct NightDetailView: View {
    /// Alle Nächte des Verlaufs, neueste zuerst.
    let items: [HistoryItem]
    /// Phasen aus Apple Health für eigene Nächte.
    let stages: [StageSpan]
    /// Anzeigename der Quelle einer Sitzung; `nil` = unbekannt.
    let sourceName: (HealthSession) -> String?
    /// Sitzungen aus Apple Health: Quelle des Schlafwerts einer eigenen Nacht.
    let sessions: [HealthSession]
    /// Schlüssel (``HistoryItem/key``) der gezeigten Nacht.
    @State private var key: String

    init(items: [HistoryItem], stages: [StageSpan], startKey: String, sourceName: @escaping (HealthSession) -> String?,
         sessions: [HealthSession] = []) {
        self.items = items
        self.stages = stages
        self.sourceName = sourceName
        self.sessions = sessions
        _key = State(initialValue: startKey)
    }

    private var index: Int? { items.firstIndex { $0.key == key } }

    var body: some View {
        TabView(selection: $key) {
            // Älteste links, neueste rechts: nach rechts wischen = vorherige Nacht.
            ForEach(items.reversed(), id: \.key) { item in
                NightDetailPage(data: NightDetailData(item: item, stages: stages), sourceName: item.session.flatMap(sourceName),
                                scoreSource: scoreSource(item))
                    .tag(item.key)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .navigationTitle(index.map { items[$0].at.formatted(date: .abbreviated, time: .omitted) } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                // `items` neueste zuerst: +1 = vorherige (ältere) Nacht, −1 = nächste.
                Button { step(1) } label: { Image(systemName: "chevron.left") }
                    .disabled(!canStep(1))
                    .accessibilityLabel(L("night_prev"))
                Button { step(-1) } label: { Image(systemName: "chevron.right") }
                    .disabled(!canStep(-1))
                    .accessibilityLabel(L("night_next"))
            }
        }
    }

    /// «Aus Apple Health.» bzw. der App, deren Sitzung die eigene Nacht am längsten überdeckt (mit Phasen); unbekannt:
    /// «Aus deinen Gesundheitsdaten.». Bei Nächten aus anderen Apps `nil`: Der Kopf nennt die Quelle schon.
    private func scoreSource(_ item: HistoryItem) -> String? {
        guard let night = item.night else { return nil }
        let session = sessions.filter { $0.hasStages && $0.start < night.wokeAt && $0.end > night.windowStart }
            .max { $0.durationMinutes < $1.durationMinutes }
        return session.flatMap(sourceName).map { L("score_source", $0) } ?? L("score_source_health")
    }

    private func canStep(_ offset: Int) -> Bool {
        guard let index else { return false }
        return items.indices.contains(index + offset)
    }

    private func step(_ offset: Int) {
        guard let index, items.indices.contains(index + offset) else { return }
        withAnimation { key = items[index + offset].key }
    }
}
