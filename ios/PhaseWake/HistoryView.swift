import PhaseWakeCore
import SwiftUI

/// Verlauf: Kennzahlen und jede Nacht – die von Phase Wake geweckten mit Bewegungskurve und, falls erlaubt,
/// Schlafphasen, dazu die übrigen Nächte aus Apple Health (gekennzeichnet).
struct HistoryView: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("healthRequested") private var healthRequested = false
    @Environment(\.scenePhase) private var scenePhase
    /// Sitzungen aus Apple Health: ein Spiegel der Quelle, beim Öffnen und bei jeder Rückkehr in den Vordergrund neu
    /// gelesen, nur im Speicher (nie gespeichert oder gesichert) – was dort geändert oder gelöscht wird, ist es hier auch.
    @State private var health = HealthNights()
    /// Daraus: Nächte für Diagramme und Trends sowie die Phasen für die eigenen Nächte (ohne ausgeblendete).
    @State private var sleeps: [SleepSummary] = []
    @State private var allStages: [StageSpan] = []
    /// Ob Apple Health schon gelesen wurde (sonst zeigt der leere Verlauf zu früh «keine Daten»).
    @State private var loaded = false
    /// Geöffnete Detailseite: Schlüssel der Nacht (``HistoryItem/key``).
    @State private var path: [String] = []

    var body: some View {
        let items = self.items
        let entries = self.entries(items)
        // Befinden zu Nächten aus anderen Apps, die im Verlauf stehen: zählt in Diagrammen und Trends wie das eigene.
        let feelings = HealthFeelings.of(items, feelings: model.healthFeelings)
        let charts = self.charts(feelings)
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 12) {
                    // Diagramme zuoberst, auch ohne Uhr-Nächte (Schlaf aus Apple Health).
                    HistoryChartsSection(data: charts, onOpen: { date in
                        if let item = HealthHistory.itemFor(items, date: date) { path.append(item.key) }
                    })
                    if entries.allSatisfy(\.isSoundsOnly) {
                        HistoryEmptyCard(healthConnected: healthRequested, loaded: loaded, onConnect: { connectHealth() })
                        // Ohne Nächte: nur Karten mit Geräuschen.
                        ForEach(entries) { entry in
                            if case .sounds(let night, let last) = entry {
                                SoundsOnlyCard(night: night, last: last)
                            }
                        }
                    } else {
                        if let summary = weekly(feelings) {
                            WeeklyCard(summary: summary)
                        }
                        if !model.nights.isEmpty { stats }
                        TrendsCard(
                            trends: Trends.compute(nights: model.nights, sleeps: sleeps, now: Date(), calendar: .current, health: feelings),
                            goalWeek: goalWeek,
                            healthConnected: healthRequested,
                            onConnectHealth: { connectHealth() },
                            habits: habitLines(model: model, sleeps: sleeps, healthConnected: healthRequested, regularShown: charts.covered.contains(.regular)),
                            covered: charts.covered
                        )
                        NoteEffectsCard(effects: NoteEffect.compute(nights: model.nights, notes: model.notes, asleepByNight: asleepByNight,
                                                                    health: feelings, calendar: .current))
                        ForEach(entries) { entry in
                            switch entry {
                            case .night(let night):
                                NightCard(night: night, stages: allStages.filter { $0.end > night.windowStart.addingTimeInterval(-14 * 3600) && $0.start < night.wokeAt.addingTimeInterval(2 * 3600) }) {
                                    path.append(HistoryItem(kind: .phaseWake, date: SleepNotes.nightDate(of: night, calendar: .current), night: night).key)
                                }
                            case .health(let session, let date):
                                HealthNightCard(session: session, nightDate: date, sourceName: sourceName(session), feeling: model.healthFeelings[date],
                                                onOpen: { path.append(HistoryItem(kind: .health, date: date, session: session).key) })
                            case .sounds(let night, let last):
                                SoundsOnlyCard(night: night, last: last)
                            }
                        }
                        if !model.nights.isEmpty { export }
                    }
                }
                .padding(.vertical)
            }
            .navigationTitle(L("history_title"))
            .navigationDestination(for: String.self) { key in
                NightDetailView(items: items, stages: allStages, startKey: key, sourceName: sourceName)
            }
            // Neu lesen beim Öffnen, nach dem Freigeben und nach «Verlauf löschen».
            .task(id: "\(healthRequested)|\(model.historyClearedAt?.timeIntervalSince1970 ?? 0)") { await reload() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { Task { await reload() } }
            }
        }
    }

    /// Liest Apple Health (nur mit Freigabe) und leitet Diagramm-Nächte und Phasen ab.
    private func reload() async {
        guard healthRequested else {
            loaded = true
            return
        }
        health = await model.health.sessions()
        sleeps = HealthHistory.summaries(health.sessions, clearedAt: model.historyClearedAt, calendar: .current)
        allStages = HealthHistory.stages(health.sessions, clearedAt: model.historyClearedAt, calendar: .current)
        loaded = true
    }

    private func connectHealth() {
        Task {
            let granted = await model.health.requestAccess()
            // Schon zuvor erteilt: `task(id:)` läuft nicht neu, also selbst lesen.
            if granted && healthRequested { await reload() }
            healthRequested = granted
        }
    }

    /// «Apple Health» für Apples eigene Quellen, sonst der Name der App; `nil` = unbekannt.
    private func sourceName(_ session: HealthSession) -> String? {
        guard let source = session.source else { return nil }
        return source == HealthSleep.appleSource ? L("health_apple") : health.names[source]
    }

    private var export: some View {
        VStack(spacing: 0) {
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

    /// Diagramme: Schlaf aus Apple Health, Weckmomente und Befinden aus dem Verlauf (auch zu Nächten aus anderen Apps).
    private func charts(_ feelings: [HealthFeeling]) -> HistoryChartsData {
        let now = Date()
        return HistoryChartsData.of(
            nights: model.nights, sleeps: sleeps, stages: allStages, notes: model.notes,
            nextTarget: model.nextPlan(now: now)?.target,
            goalMinutes: model.sleepGoal, now: now, calendar: .current, health: feelings
        )
    }

    /// Verlauf aus eigenen Nächten und Nächten aus Apple Health, neueste zuerst (`HealthHistory.merge`).
    private var items: [HistoryItem] {
        HealthHistory.merge(nights: model.nights, sessions: health.sessions, clearedAt: model.historyClearedAt, calendar: .current)
    }

    /// Nächte (neueste zuerst) und dazwischen nach Zeit die Karten nur mit Geräuschen (Nachtdaten ohne Nacht).
    private func entries(_ items: [HistoryItem]) -> [HistoryEntry] {
        var pending = SoundEvents.nightsWithoutRecord(model.recorder.clips, recorded: Set(items.map(\.date)))[...]
        var out: [HistoryEntry] = []
        for item in items {
            while let first = pending.first, first.last > item.at {
                out.append(.sounds(night: first.night, last: first.last))
                pending = pending.dropFirst()
            }
            if let night = item.night {
                out.append(.night(night))
            } else if let session = item.session {
                out.append(.health(session, date: item.date))
            }
        }
        out += pending.map { HistoryEntry.sounds(night: $0.night, last: $0.last) }
        return out
    }

    /// Geschlafene Minuten je Nachtdatum (Einschlafen − 12 h ergibt den Abend); bei zwei Einträgen der längere.
    private var asleepByNight: [String: Int] {
        sleeps.reduce(into: [:]) { out, sleep in
            let date = SleepNotes.nightDate(sleep.start, calendar: .current)
            out[date] = max(out[date] ?? 0, sleep.asleepMinutes)
        }
    }

    /// Wochenrückblick am Sonntag und Montag; `nil` an anderen Tagen oder ohne Daten.
    private func weekly(_ feelings: [HealthFeeling]) -> WeeklySummary? {
        let today = NightDate.today(Date(), calendar: .current)
        guard WeeklySummary.cardVisible(today: today) else { return nil }
        let summary = WeeklySummary.of(nights: model.nights, sleeps: sleeps, notes: model.notes, calendar: .current,
                                       goalMinutes: model.sleepGoal, weekEnd: WeeklySummary.weekEnd(today: today), health: feelings)
        return summary.hasData ? summary : nil
    }

    /// Schlafziel der letzten 7 Nächte; `nil` ohne Daten aus Apple Health.
    private var goalWeek: (reached: Int, total: Int)? {
        let weekAgo = Date().addingTimeInterval(-7 * 86_400)
        // Nachtdatum nach dem Ende der Nacht, wie Android (`SleepGoal.nightsOf`).
        let recent = SleepGoal.nightsOf(sleeps.filter { $0.end > weekAgo }, calendar: .current)
        let status = SleepGoal.weekStatus(recent, goalMinutes: model.sleepGoal)
        return status.total > 0 ? status : nil
    }

    private var stats: some View {
        let average = model.nights.map(\.minutesBeforeTarget).reduce(0, +) / max(1, model.nights.count)
        // Nur Nächte, in denen früher wecken erlaubt war (ohne «Genau» und kurze Nächte); ohne solche keine Angabe.
        let light = NightHistory.lightShare(model.nights)
        return HStack(spacing: 12) {
            Text(L("history_average", average)).frame(maxWidth: .infinity, alignment: .leading)
            if let light {
                Text(L("history_light_share", light)).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
        .card()
    }
}

/// Eintrag im Verlauf: eine aufgezeichnete Nacht, eine Nacht aus Apple Health oder nur Geräusche eines Nachtdatums.
private enum HistoryEntry: Identifiable {
    case night(NightRecord)
    /// `date` = Nachtdatum der Sitzung (`HistoryItem.date`).
    case health(HealthSession, date: String)
    /// `last` = Beginn des jüngsten Ausschnitts.
    case sounds(night: String, last: Date)

    var id: String {
        switch self {
        case .night(let night): night.listId
        case .health(_, let date): "health|\(date)"
        case .sounds(let night, _): "sounds|\(night)"
        }
    }

    /// Nur Geräusche, keine Nacht.
    var isSoundsOnly: Bool {
        if case .sounds = self { return true }
        return false
    }
}

/// Geräusche einer Nacht ohne aufgezeichnete Nacht: Datum (des jüngsten Ausschnitts, wie bei den Nächten
/// das Weckdatum) und die Bilanz, die die Ausschnitte öffnet.
private struct SoundsOnlyCard: View {
    let night: String
    let last: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(last.formatted(date: .abbreviated, time: .omitted)).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            NightSoundsButton(night: night, title: last.formatted(date: .abbreviated, time: .omitted))
        }
        .card()
    }
}

private extension NightRecord {
    /// Eine Nacht ist Wecker plus Fensterbeginn (zwei Wecker können gleich beginnen).
    var listId: String { "\(alarmId)|\(windowStart.timeIntervalSince1970)" }
}

/// Nachtkarte einer Phase-Wake-Nacht: Datum und Weckzeit, kleines Schlafprofil (ohne Phasen die Bewegungskurve),
/// Summen, Weckgrund, Datenqualität und der Abgleich mit der Phase beim Wecken; ein Tipp öffnet die Detailseite.
/// Notizen und Geräusche bleiben eigene Knöpfe.
private struct NightCard: View {
    let night: NightRecord
    let stages: [StageSpan]
    /// Öffnet die Detailseite dieser Nacht.
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: onOpen) { summary }
                .buttonStyle(.plain)
                .accessibilityHint(L("night_open"))
            NightNotesButton(nightDate: SleepNotes.nightDate(of: night, calendar: .current),
                             title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
            NightSoundsButton(night: SleepNotes.nightDate(of: night, calendar: .current),
                              title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
        }
        .card()
    }

    /// Alles ausser den Knöpfen: der Teil, der die Detailseite öffnet.
    private var summary: some View {
        let hypnogram = ChartData.hypnogram(stages, night: night)
        let totals = StageStats.forNight(stages, night: night).map(stageTotalsLine)
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(night.wokeAt.formatted(date: .abbreviated, time: .omitted)).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(night.wokeAt)).font(Fonts.manrope(16, .bold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            if let hypnogram {
                MiniHypnogram(steps: hypnogram.steps, totalMinutes: hypnogram.totalMinutes, wokeAt: hypnogram.wokeAt)
                    .frame(height: miniHypnogramHeight)
            } else {
                NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30), unmeasured: unmeasuredEpochs(night)).frame(height: 44)
            }
            if let totals, !totals.isEmpty {
                Text(totals).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            Text(nightSummary(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            Text(nightDetails(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            if let unmeasured = unmeasuredText(night) {
                Text(unmeasured).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            DetailsRow()
        }
        .contentShape(Rectangle())
    }
}
