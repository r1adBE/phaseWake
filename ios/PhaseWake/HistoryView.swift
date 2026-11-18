import PhaseWakeCore
import SwiftUI

/// Verlauf: Kennzahlen und jede Nacht – die von Phase Wake geweckten mit Bewegungskurve und, falls erlaubt,
/// Schlafphasen, dazu die übrigen Nächte aus Apple Health (gekennzeichnet).
struct HistoryView: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("healthRequested") private var healthRequested = false
    @Environment(\.scenePhase) private var scenePhase
    /// Sitzungen aus Apple Health: ein Spiegel der Quelle, beim Öffnen und bei jeder Rückkehr in den Vordergrund neu
    /// gelesen, nur im Speicher (nie gespeichert oder gesichert) – was dort geändert oder gelöscht wird, ist es hier
    /// auch.
    @State private var health = HealthNights()
    /// Daraus: Nächte für den Schnitt der Wochen und Jahre und die Übersicht «Monate» sowie die Phasen der eigenen
    /// Nächte (ohne ausgeblendete).
    @State private var sleeps: [SleepSummary] = []
    /// Kennzahlen (Diagramme, Trends, Notizen, Wochenrückblick) wie bisher nur über die letzten 90 Tage
    /// (`HealthHistory.forInsights`), nicht den ganzen Verlauf: Sitzungen und Nächte daraus.
    @State private var recentSessions: [HealthSession] = []
    @State private var recentSleeps: [SleepSummary] = []
    @State private var allStages: [StageSpan] = []
    /// Ob Apple Health schon gelesen wurde (sonst zeigt der leere Verlauf zu früh «keine Daten»).
    @State private var loaded = false
    /// Geöffnete Detailseite: Schlüssel der Nacht (``HistoryItem/key``).
    @State private var path: [String] = []
    /// Aufgeklappte Wochen und Jahre der Liste: anfangs alle zu, nie gespeichert.
    @State private var open: Set<String> = []

    /// Phasen für die Karte einer eigenen Nacht: so lange nach dem Wecken (Sekunden), samt Weiterschlafen – Beginn bis
    /// `HealthHistory.continuationGap` nach dem Wecken, kürzer als `HealthHistory.minNightMinutes`. Wie `STAGES_AFTER`
    /// (Android).
    private static let stagesAfter: TimeInterval = HealthHistory.continuationGap
        + TimeInterval(HealthHistory.minNightMinutes * 60)

    var body: some View {
        let items = self.items
        let entries = self.entries(items)
        // Kennzahlen über die jüngsten 90 eigenen Nächte (der Verlauf reicht 2 Jahre zurück).
        let recent = NightHistory.latest(model.nights)
        // Befinden zu Nächten aus anderen Apps, die im Verlauf stehen: zählt in Diagrammen und Trends wie das eigene.
        let feelings = HealthFeelings.of(HealthHistory.merge(nights: recent, sessions: recentSessions,
                                                             clearedAt: model.historyClearedAt,
                                                             calendar: .gregorianLocal),
                                         feelings: model.healthFeelings)
        let charts = self.charts(feelings)
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: 12) {
                    // Diagramme zuoberst, auch ohne Uhr-Nächte (Schlaf aus Apple Health).
                    HistoryChartsSection(data: charts, onOpen: { date in
                        if let item = HealthHistory.itemFor(items, date: date) { path.append(item.key) }
                    })
                    if entries.allSatisfy(\.isSoundsOnly) {
                        HistoryEmptyCard(healthConnected: healthRequested, loaded: loaded,
                                         onConnect: { connectHealth() })
                        // Ohne Nächte: nur Karten mit Geräuschen.
                        ForEach(entries) { entry in
                            if case .sounds(let night, let last) = entry {
                                SoundsOnlyCard(night: night, last: last)
                            }
                        }
                    } else {
                        // Auffälligkeiten der letzten 7 Nächte: alle an einer Stelle, in den Trends (wie Android).
                        let facts = NightFacts.of(health.sessions, clearedAt: model.historyClearedAt,
                                                  calendar: .gregorianLocal,
                                                  own: PhoneLearning.shared.recordings.compactMap(\.ownOnset),
                                                  watch: model.nights.compactMap(OwnOnset.of))
                        if let summary = weekly(feelings, nights: recent) {
                            WeeklyCard(summary: summary, deep: Findings.weekDeep(facts, weekEnd: summary.to))
                        }
                        if !recent.isEmpty { stats(recent) }
                        TrendsCard(
                            trends: Trends.compute(nights: recent, sleeps: recentSleeps, now: Date(),
                                                   calendar: .gregorianLocal, health: feelings),
                            goalWeek: goalWeek,
                            healthConnected: healthRequested,
                            onConnectHealth: { connectHealth() },
                            habits: habitLines(model: model, sleeps: recentSleeps, healthConnected: healthRequested,
                                               regularShown: charts.covered.contains(.regular)),
                            covered: charts.covered,
                            findings: Findings.of(facts, today: SleepNotes.nightDate(Date(), calendar: .gregorianLocal),
                                                  calendar: .gregorianLocal)
                        )
                        NoteEffectsCard(effects: NoteEffect.compute(nights: recent, notes: model.notes,
                                                                    asleepByNight: recentAsleep,
                                                                    health: feelings, calendar: .gregorianLocal))
                        // Die neusten Nächte einzeln, ältere je Woche bzw. Jahr (aufklappbar).
                        let today = NightDate.today(Date(), calendar: .gregorianLocal)
                        let asleep = asleepByNight
                        let compact = CompactRowsContext(asleep: asleep, healthFeelings: model.healthFeelings,
                                                         goalMinutes: model.sleepGoal,
                                                         recordings: PhoneLearning.shared.recordings,
                                                         stages: stages(of:), open: { path.append($0) })
                        HistoryGroupList(groups: HistoryEntry.groups(entries, today: today), open: $open,
                                         summary: { HistoryEntry.summary($0, asleep: asleep,
                                                                         healthFeelings: model.healthFeelings) },
                                         today: today) { entry in
                            switch entry {
                            case .night(let night):
                                NightCard(night: night, stages: stages(of: night),
                                          goalMinutes: model.sleepGoal, recordings: PhoneLearning.shared.recordings) {
                                    let date = SleepNotes.nightDate(of: night, calendar: .gregorianLocal)
                                    path.append(HistoryItem(kind: .phaseWake, date: date, night: night).key)
                                }
                            case .health(let session, let date):
                                HealthNightCard(session: session, nightDate: date, feeling: model.healthFeelings[date],
                                                onOpen: { path.append(HistoryItem(kind: .health, date: date,
                                                                                  session: session).key) },
                                                score: nightScore(stages: StageStats.forSession(session),
                                                                  session: session,
                                                                  goalMinutes: model.sleepGoal)?.result)
                            case .sounds(let night, let last):
                                SoundsOnlyCard(night: night, last: last)
                            case .recording(let recording):
                                RecordingCard(recording: recording)
                            }
                        } compact: { entry in
                            HistoryCompactEntry(entry: entry, context: compact)
                        }
                        healthHint(entries)
                        if !model.nights.isEmpty || !PhoneLearning.shared.recordings.isEmpty { export }
                    }
                }
                .padding(.vertical)
            }
            .skyPage()
            .navigationTitle(L("history_title"))
            .navigationDestination(for: String.self) { key in
                NightDetailView(items: items, stages: allStages, startKey: key, sourceName: sourceName,
                                sessions: health.sessions)
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
        sleeps = HealthHistory.summaries(health.sessions, clearedAt: model.historyClearedAt, calendar: .gregorianLocal)
        recentSessions = HealthHistory.forInsights(nights: model.nights, sessions: health.sessions,
                                                   now: Date()).sessions
        recentSleeps = HealthHistory.summaries(recentSessions, clearedAt: model.historyClearedAt,
                                               calendar: .gregorianLocal)
        allStages = HealthHistory.stages(health.sessions, clearedAt: model.historyClearedAt, calendar: .gregorianLocal)
        PhoneWidgetSync.publishAsleep(model, sleeps: sleeps)
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

    /// Phasen für die Karte bzw. Zeile einer eigenen Nacht: Beginn bis `stagesAfter` nach dem Wecken.
    private func stages(of night: NightRecord) -> [StageSpan] {
        allStages.filter {
            $0.end > night.windowStart.addingTimeInterval(-14 * 3600)
                && $0.start < night.wokeAt.addingTimeInterval(Self.stagesAfter)
        }
    }

    /// Hinweis zur Quelle der Nächte aus Apple Health, einmal unter der Liste statt auf jeder Karte; aus mehreren
    /// Quellen einmal die allgemeine Fassung. Wie `healthHintItem` (Android).
    @ViewBuilder private func healthHint(_ entries: [HistoryEntry]) -> some View {
        let hints = Set(entries.compactMap { entry -> String? in
            if case .health(let session, _) = entry { return healthLive(sourceName: sourceName(session)) }
            return nil
        })
        if !hints.isEmpty {
            Text(hints.count == 1 ? hints.first ?? "" : healthLive(sourceName: nil))
                .font(Fonts.manrope(11, relativeTo: .caption2))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 32)
        }
    }

    private var export: some View {
        VStack(spacing: 0) {
            HistoryExportLink { Label(L("history_export"), systemImage: "square.and.arrow.up") }
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
        let input = HistoryChartsData.Input(
            nights: NightHistory.latest(model.nights), sleeps: recentSleeps, monthSleeps: sleeps, stages: allStages,
            notes: model.notes,
            health: feelings,
            nextTarget: model.nextPlan(now: now)?.target,
            goalMinutes: model.sleepGoal, latencyMinutes: model.learnedLatency,
            // Erster Tag der Woche nach der Sprache des Geräts (Calendar: 1 = Sonntag; Weekday: 1 = Montag).
            firstDay: Weekday(rawValue: (Calendar.autoupdatingCurrent.firstWeekday + 5) % 7 + 1) ?? .monday
        )
        return HistoryChartsData.of(input, now: now, calendar: .gregorianLocal)
    }

    /// Verlauf aus eigenen Nächten und Nächten aus Apple Health, neueste zuerst (`HealthHistory.merge`).
    private var items: [HistoryItem] {
        HealthHistory.merge(nights: model.nights, sessions: health.sessions, clearedAt: model.historyClearedAt,
                            calendar: .gregorianLocal)
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
        return Self.withRecordings(out, PhoneLearning.shared.recordings)
    }

    /// Reiht die Aufzeichnungen der Uhr (``RecordingCard``) nach ihrem Ende in `entries` (neueste zuerst) ein, wie
    /// `withRecordings` (Android).
    private static func withRecordings(_ entries: [HistoryEntry], _ recordings: [NightRecording]) -> [HistoryEntry] {
        guard !recordings.isEmpty else { return entries }
        var pending = recordings.sorted { $0.until > $1.until }[...]
        var out: [HistoryEntry] = []
        for entry in entries {
            while let first = pending.first, first.until > entry.at {
                out.append(.recording(first))
                pending = pending.dropFirst()
            }
            out.append(entry)
        }
        return out + pending.map { HistoryEntry.recording($0) }
    }

    /// Geschlafene Minuten je Nachtdatum (Mitte der Nacht, wie Diagramme und Android); bei zwei Einträgen der längere.
    private var asleepByNight: [String: Int] { NoteEffect.asleepByNight(sleeps, calendar: .gregorianLocal) }

    /// Wie ``asleepByNight``, nur die letzten 90 Tage (Kennzahlen).
    private var recentAsleep: [String: Int] { NoteEffect.asleepByNight(recentSleeps, calendar: .gregorianLocal) }

    /// Wochenrückblick am Sonntag und Montag; `nil` an anderen Tagen oder ohne Daten.
    private func weekly(_ feelings: [HealthFeeling], nights: [NightRecord]) -> WeeklySummary? {
        let today = NightDate.today(Date(), calendar: .gregorianLocal)
        guard WeeklySummary.cardVisible(today: today) else { return nil }
        let summary = WeeklySummary.of(nights: nights, sleeps: recentSleeps, notes: model.notes,
                                       calendar: .gregorianLocal,
                                       goalMinutes: model.sleepGoal, weekEnd: WeeklySummary.weekEnd(today: today),
                                       health: feelings)
        return summary.hasData ? summary : nil
    }

    /// Schlafziel der jüngsten 7 Nachtdaten im Fenster der Kennzahlen (90 Tage), wie Android und die Startseite
    /// (``HomeSleepWeek``); `nil` ohne Daten aus Apple Health.
    private var goalWeek: (reached: Int, total: Int)? {
        // Nachtdatum über die Mitte der Nacht, wie Android (`SleepGoal.nightsOf`).
        let recent = SleepGoal.nightsOf(recentSleeps, calendar: .gregorianLocal)
        let status = SleepGoal.weekStatus(recent, goalMinutes: model.sleepGoal)
        return status.total > 0 ? status : nil
    }

    /// Kennzahlen über `nights` (die jüngsten 90, nicht der ganze Verlauf).
    private func stats(_ nights: [NightRecord]) -> some View {
        let average = nights.map(\.minutesBeforeTarget).reduce(0, +) / max(1, nights.count)
        // Nur Nächte, in denen früher wecken erlaubt war (ohne «Genau» und kurze Nächte); ohne solche keine Angabe.
        let light = NightHistory.lightShare(nights)
        return HStack(spacing: 12) {
            Text(L("history_average", average)).frame(maxWidth: .infinity, alignment: .leading)
            if let light {
                Text(L("history_light_share", light)).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
        .card(tinted: true)
    }
}

/// Geräusche einer Nacht ohne aufgezeichnete Nacht: Datum (des jüngsten Ausschnitts, wie bei den Nächten
/// das Weckdatum) und die Bilanz, die die Ausschnitte öffnet.
private struct SoundsOnlyCard: View {
    let night: String
    let last: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(last.formatted(date: .abbreviated, time: .omitted))
                .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            NightSoundsButton(night: night, title: last.formatted(date: .abbreviated, time: .omitted))
        }
        .card()
    }
}

/// Nachtkarte einer Phase-Wake-Nacht: Datum und Weckzeit, kleines Schlafprofil (ohne Phasen die Bewegungskurve),
/// Summen, Weckgrund, Datenqualität und der Abgleich mit der Phase beim Wecken; ein Tipp öffnet die Detailseite.
/// Notizen (in einer Zeile mit «Details ›», ``NightActionsRow``) und Geräusche bleiben eigene Knöpfe.
private struct NightCard: View {
    let night: NightRecord
    let stages: [StageSpan]
    /// Schlafziel in Minuten für den Schlafwert.
    let goalMinutes: Int
    /// Aufzeichnungen der Uhr: Schlafwert ohne Phasen (vorläufig), wenn eine die Nacht überdeckt.
    let recordings: [NightRecording]
    /// Öffnet die Detailseite dieser Nacht.
    let onOpen: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: onOpen) { summary }
                .buttonStyle(.plain)
                .accessibilityHint(L("night_open"))
            NightActionsRow(nightDate: SleepNotes.nightDate(of: night, calendar: .gregorianLocal),
                            title: night.wokeAt.formatted(date: .abbreviated, time: .omitted), onOpen: onOpen)
            NightSoundsButton(night: SleepNotes.nightDate(of: night, calendar: .gregorianLocal),
                              title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
        }
        .card()
    }

    /// Alles ausser den Knöpfen: der Teil, der die Detailseite öffnet.
    private var summary: some View {
        let hypnogram = ChartData.hypnogram(stages, night: night)
        let nightStages = StageStats.forNight(stages, night: night)
        let totals = nightStages.map(stageTotalsLine)
        // Ohne Phasen aus einer Aufzeichnung der Uhr mit Einschlafzeit (vorläufig), sonst kein Schlafwert.
        let score = nightScore(stages: nightStages, session: nil, goalMinutes: goalMinutes, night: night,
                               recordings: recordings)?.result
        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(night.wokeAt.formatted(date: .abbreviated, time: .omitted))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(night.wokeAt))
                    .font(Fonts.manrope(16, .bold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            if let score { ScoreLine(result: score) }
            if let hypnogram {
                MiniHypnogram(steps: hypnogram.steps, totalMinutes: hypnogram.totalMinutes, wokeAt: hypnogram.wokeAt)
                    .frame(height: miniHypnogramHeight)
            } else {
                NightBars(epochs: night.epochs,
                          totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30),
                          unmeasured: unmeasuredEpochs(night)).frame(height: 44)
            }
            if let totals, !totals.isEmpty {
                Text(totals).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            Text(nightSummary(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            Text(nightDetails(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            if let unmeasured = unmeasuredText(night) {
                Text(unmeasured).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            }
        }
        .contentShape(Rectangle())
    }
}
