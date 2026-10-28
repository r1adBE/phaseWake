import PhaseWakeCore
import SwiftUI

/// Verlauf: Kennzahlen und jede Nacht mit Bewegungskurve und – falls erlaubt – Schlafphasen.
struct HistoryView: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("healthRequested") private var healthRequested = false
    @State private var sleeps: [SleepSummary] = []
    /// Schlafphasen für den ganzen Verlauf in einer Abfrage statt einer je Nacht.
    @State private var allStages: [StageSpan] = []

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
                        if let weekly {
                            WeeklyCard(summary: weekly)
                        }
                        stats
                        TrendsCard(
                            trends: Trends.compute(nights: model.nights, sleeps: sleeps, now: Date(), calendar: .current),
                            goalWeek: goalWeek,
                            healthConnected: healthRequested,
                            onConnectHealth: { Task { healthRequested = await model.health.requestAccess(); sleeps = await model.health.recentSleep(days: Self.sleepDays) } },
                            habits: habitLines(model: model, sleeps: sleeps, healthConnected: healthRequested)
                        )
                        NoteEffectsCard(effects: NoteEffect.compute(nights: model.nights, notes: model.notes, asleepByNight: asleepByNight, calendar: .current))
                        ForEach(model.nights.reversed(), id: \.listId) { night in
                            NightCard(night: night, stages: allStages.filter { $0.end > night.windowStart.addingTimeInterval(-14 * 3600) && $0.start < night.wokeAt.addingTimeInterval(2 * 3600) })
                        }
                        ShareLink(item: exportFile(model.nights, notes: model.notes), preview: SharePreview(L("history_export_subject"))) {
                            Label(L("history_export"), systemImage: "square.and.arrow.up")
                        }
                        .padding(.top)
                        Text(L("export_contents_hint"))
                            .font(Fonts.manrope(12, relativeTo: .caption))
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                            .padding(.bottom)
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle(L("history_title"))
            .task(id: healthRequested) {
                guard healthRequested else { return }
                sleeps = await model.health.recentSleep(days: Self.sleepDays)
                if let first = model.nights.first, let last = model.nights.last {
                    allStages = await model.health.stages(from: first.windowStart.addingTimeInterval(-14 * 3600), to: last.wokeAt.addingTimeInterval(2 * 3600))
                }
            }
        }
    }

    /// Schlafdauer für Trends und für die Wirkung der Notizen (90 Nächte).
    private static let sleepDays: Double = Double(NightHistory.maxNights + 2)

    /// Geschlafene Minuten je Nachtdatum (Einschlafen − 12 h ergibt den Abend); bei zwei Einträgen der längere.
    private var asleepByNight: [String: Int] {
        sleeps.reduce(into: [:]) { out, sleep in
            let date = SleepNotes.nightDate(sleep.start, calendar: .current)
            out[date] = max(out[date] ?? 0, sleep.asleepMinutes)
        }
    }

    /// Wochenrückblick am Sonntag und Montag; `nil` an anderen Tagen oder ohne Daten.
    private var weekly: WeeklySummary? {
        let today = NightDate.today(Date(), calendar: .current)
        guard WeeklySummary.cardVisible(today: today) else { return nil }
        let summary = WeeklySummary.of(nights: model.nights, sleeps: sleeps, notes: model.notes, calendar: .current,
                                       goalMinutes: model.sleepGoal, weekEnd: WeeklySummary.weekEnd(today: today))
        return summary.hasData ? summary : nil
    }

    /// Schlafziel der letzten 7 Nächte; `nil` ohne Daten aus Apple Health.
    private var goalWeek: (reached: Int, total: Int)? {
        let weekAgo = Date().addingTimeInterval(-7 * 86_400)
        let recent = sleeps.filter { $0.end > weekAgo }.map { (date: SleepNotes.nightDate($0.start, calendar: .current), asleepMinutes: $0.asleepMinutes) }
        let status = SleepGoal.weekStatus(recent, goalMinutes: model.sleepGoal)
        return status.total > 0 ? status : nil
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

private extension NightRecord {
    /// Eine Nacht ist Wecker plus Fensterbeginn (zwei Wecker können gleich beginnen).
    var listId: String { "\(alarmId)|\(windowStart.timeIntervalSince1970)" }
}

private struct NightCard: View {
    @Environment(PhoneModel.self) private var model
    let night: NightRecord
    let stages: [StageSpan]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(night.wokeAt.formatted(date: .abbreviated, time: .omitted)).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(night.wokeAt)).font(Fonts.manrope(16, .bold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30), unmeasured: unmeasuredEpochs(night)).frame(height: 44)
            Text(nightSummary(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            Text(nightDetails(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            if let unmeasured = unmeasuredText(night) {
                Text(unmeasured).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            if let verdict {
                Text(verdict.text).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(verdict.good ? Color.accentColor : .red)
            }
            NightNotesButton(nightDate: SleepNotes.nightDate(of: night, calendar: .current),
                             title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
            NightSoundsButton(night: SleepNotes.nightDate(of: night, calendar: .current),
                              title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
        }
        .card()
        .accessibilityElement(children: .combine)
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
