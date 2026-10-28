import PhaseWakeCore
import SwiftUI

/// Verlauf: Kennzahlen und jede Nacht mit Bewegungskurve und – falls erlaubt – Schlafphasen.
struct HistoryView: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("healthRequested") private var healthRequested = false
    @State private var sleeps: [SleepSummary] = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    if model.nights.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(L("history_empty_title")).font(Fonts.manrope(17, .semibold, relativeTo: .headline))
                            Text(L("history_empty_text")).font(Fonts.manrope(14, relativeTo: .body)).foregroundStyle(.secondary)
                        }
                        .card()
                    } else {
                        stats
                        TrendsCard(
                            trends: Trends.compute(nights: model.nights, sleeps: sleeps, now: Date(), calendar: .current),
                            healthConnected: healthRequested,
                            onConnectHealth: { Task { healthRequested = await model.health.requestAccess(); sleeps = await model.health.recentSleep() } }
                        )
                        ForEach(model.nights.reversed(), id: \.windowStart) { NightCard(night: $0) }
                        ShareLink(item: NightHistory.exportCsv(model.nights), preview: SharePreview(L("history_export_subject"))) {
                            Label(L("history_export"), systemImage: "square.and.arrow.up")
                        }
                        .padding()
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle(L("history_title"))
            .task(id: healthRequested) { if healthRequested { sleeps = await model.health.recentSleep() } }
        }
    }

    private var stats: some View {
        let average = model.nights.map(\.minutesBeforeTarget).reduce(0, +) / max(1, model.nights.count)
        let light = 100 * model.nights.filter { $0.reason == .lightSleep }.count / max(1, model.nights.count)
        return HStack(spacing: 12) {
            Text(L("history_average", average)).frame(maxWidth: .infinity, alignment: .leading)
            Text(L("history_light_share", light)).frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
        .card()
    }
}

private struct NightCard: View {
    @Environment(PhoneModel.self) private var model
    let night: NightRecord
    @State private var stages: [StageSpan] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(night.wokeAt.formatted(date: .abbreviated, time: .omitted)).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(night.wokeAt)).font(Fonts.manrope(16, .bold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30)).frame(height: 44)
            Text(nightSummary(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            Text(nightDetails(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            if let verdict {
                Text(verdict.text).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(verdict.good ? Color.accentColor : .red)
            }
        }
        .card()
        .accessibilityElement(children: .combine)
        .task { stages = await model.health.stages(for: night) }
    }

    private var verdict: (text: String, good: Bool)? {
        switch StageCheck.stageAtWake(stages, wokeAt: night.wokeAt) {
        case .light: (L("verdict_good", L("stage_light")), true)
        case .rem: (L("verdict_good", L("stage_rem")), true)
        case .awake: (L("verdict_good", L("stage_awake")), true)
        case .deep: (L("verdict_deep"), false)
        case .unknown, nil: nil
        }
    }
}
