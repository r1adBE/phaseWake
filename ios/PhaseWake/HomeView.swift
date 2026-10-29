import PhaseWakeCore
import SwiftUI

/// Startseite: Kopf im Nachthimmel mit dem nächsten Fenster, letzte Nacht, alle Wecker.
/// Dunkel ist die ganze Seite Nachthimmel, hell nur der Kopf.
struct HomeView: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var editing: AlarmSpec?
    @State private var creating = false
    @State private var failedChecks: [PhoneCheck] = []
    @State private var nightstand = false
    @State private var sounds = false
    /// Gescrollt: «Neuer Wecker» nur noch als runder Knopf, damit er ruhiger über den Karten liegt.
    @State private var fabCompact = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ZStack {
                if scheme == .dark { NightSky() }
                ScrollView {
                    VStack(spacing: 16) {
                        TimelineView(.everyMinute) { context in
                            header(now: context.date)
                        }
                        TimelineView(.everyMinute) { context in
                            // Eine früh geweckte Nacht zählt nicht mehr (wie Nacht-Check und Widget).
                            let plan = model.nextPlan(now: context.date)
                            VStack(spacing: 16) {
                                // Nacht-Check nur, wenn ein Wecker ansteht.
                                if plan != nil {
                                    NightCheckCard(failed: failedChecks) { check in
                                        Task { await NightCheck.fix(check); failedChecks = await NightCheck.collect() }
                                    }
                                }
                                TransientStatusCard()
                                if model.transient.nap == nil { NapCard() }
                                if let plan, model.alarms.first(where: { $0.id == plan.alarmId })?.sunrise != .off {
                                    NightstandButton { nightstand = true }
                                }
                                // Abends bis zum Weckfenster: Notizen für die kommende Nacht.
                                let next = model.nextPlan(now: context.date)
                                let evening = SleepNotes.entryVisible(now: context.date, nextWindowStart: next?.windowStart, calendar: .current)
                                let tonight = SleepNotes.nightDate(context.date, calendar: .current)
                                if evening {
                                    TonightNotesCard(nightDate: tonight)
                                    RoutineCard(night: tonight)
                                }
                                // Geräusche: abends zum Einschalten, und solange die Aufnahme läuft.
                                if evening || model.recorder.running {
                                    SoundRecordingCard(night: tonight)
                                }
                                SoundsButton { sounds = true }
                                if let night = model.nights.last {
                                    LastNightCard(
                                        night: night,
                                        now: context.date,
                                        // Ungemessene erste Minuten der Apple Watch zählen nicht als Lücke.
                                        hint: MissHint.of(model.alarms.first { $0.id == night.alarmId }, nights: model.nights.map(measuredPart))
                                    ) { model.setFeeling($0, for: night) }
                                }
                                // Morgenbericht ohne aufgezeichnete Nacht (z. B. Uhr nicht getragen): nur die Geräusche.
                                // Nicht während der Aufnahme und nicht für die Nacht, die gerade läuft.
                                let recorded = Set(model.nights.last.map { [SleepNotes.nightDate(of: $0, calendar: .current)] } ?? [])
                                if let sounds = SoundEvents.nightsWithoutRecord(model.recorder.clips, recorded: recorded).first,
                                   context.date.timeIntervalSince(sounds.last) < 12 * 3600, !model.recorder.running,
                                   !(evening && tonight == sounds.night) {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(L("home_morning")).font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                                        NightSoundsButton(night: sounds.night, title: sounds.last.formatted(date: .abbreviated, time: .omitted))
                                    }
                                    .card()
                                }
                            }
                        }
                        if !model.alarms.isEmpty {
                            Text(L("home_alarms"))
                                .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 20)
                                .padding(.top, 8)
                            ForEach(model.alarms) { alarm in
                                AlarmRow(alarm: alarm) { editing = alarm }
                            }
                            if !model.canAddAlarm {
                                Text(L("home_max_alarms", PhoneModel.maxAlarms))
                                    .font(Fonts.manrope(12, relativeTo: .caption))
                                    .foregroundStyle(Brand.tertiaryText(scheme))
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.horizontal, 20)
                            }
                        }
                    }
                    .padding(.bottom, 16)
                }
                .ignoresSafeArea(edges: .top)
                .onScrolledPast(8) { scrolled in
                    withAnimation(.snappy) { fabCompact = scrolled }
                }
                // Der Knopf als Einschub unten: Die Liste endet über ihm, die letzte Karte bleibt ganz sichtbar.
                .safeAreaInset(edge: .bottom, alignment: .trailing) {
                    if !model.alarms.isEmpty { addButton }
                }
            }
            .background(scheme == .dark ? Color.clear : Brand.paper)
            .task(id: scenePhase) {
                // Bei jedem Öffnen und danach jede halbe Minute prüfen.
                while scenePhase == .active, !Task.isCancelled {
                    failedChecks = await NightCheck.collect()
                    PhoneWidgetSync.publish(model, failed: failedChecks)
                    // «Geräusche aufnehmen»: ab der Bettzeit (Vorgabe) bzw. am Abend (selbst eingeschaltet) von selbst starten.
                    await model.autoStartSoundRec()
                    try? await Task.sleep(for: .seconds(30))
                }
            }
            .fullScreenCover(isPresented: $nightstand) { NightstandView() }
            .sheet(isPresented: $sounds) { SoundsSheet().environment(model) }
            .sheet(item: $editing) { EditorView(existing: $0) }
            .sheet(isPresented: Binding(get: { model.transient.showResult }, set: { model.transient.showResult = $0 }),
                   onDismiss: { model.transient.closeResult() }) {
                if let run = model.transient.run { TestResultSheet(run: run).environment(model) }
            }
            .sheet(isPresented: $creating) { EditorView(existing: nil) }
        }
    }

    /// «Neuer Wecker»: ganz oben ausgeschrieben, beim Scrollen nur das Plus.
    private var addButton: some View {
        Button { creating = true } label: {
            Group {
                if fabCompact {
                    Image(systemName: "plus").frame(width: 24, height: 24).padding(16)
                } else {
                    Label(L("home_add_alarm"), systemImage: "plus").padding(.horizontal, 20).padding(.vertical, 16)
                }
            }
            .font(Fonts.manrope(15, .bold, relativeTo: .headline))
        }
        .accessibilityLabel(L("home_add_alarm"))
        .buttonStyle(.borderedProminent)
        .tint(Brand.accent(scheme))
        .foregroundStyle(Brand.onAccent(scheme))
        .clipShape(RoundedRectangle(cornerRadius: Brand.cardRadius))
        .padding(16)
        // Höchstens acht Wecker; der Hinweis steht unter der Liste.
        .disabled(!model.canAddAlarm)
    }

    private func header(now: Date) -> some View {
        let plan = model.nextPlan(now: now)
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 10) {
                LogoMark().frame(width: 48, height: 48)
                Text("Phase Wake").font(Fonts.manrope(20, .bold, relativeTo: .title3))
            }
            .padding(.bottom, 26)
            Text(L(greetingKey(now))).font(Fonts.manrope(15, .semibold, relativeTo: .subheadline)).foregroundStyle(Brand.muted)
            if let plan, let alarm = model.alarms.first(where: { $0.id == plan.alarmId }) {
                Text(Format.time(plan.windowStart) + " – " + Format.time(plan.target))
                    .font(Fonts.manrope(44, .heavy, relativeTo: .largeTitle))
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                let day = Format.day(plan.target, todayKey: "home_next_today", tomorrowKey: "home_next_tomorrow")
                let minutes = Int(plan.windowStart.timeIntervalSince(now) / 60)
                // Zwei Zeilen: wann und welcher – dann wie lange noch (oder: Fenster läuft).
                Text([day, alarm.label].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(Fonts.manrope(14, relativeTo: .subheadline))
                    .foregroundStyle(Brand.muted)
                Text(minutes > 0 ? L("home_next_in", Format.duration(minutes: minutes)) : L("home_window_running"))
                    .font(Fonts.manrope(14, relativeTo: .subheadline))
                    .foregroundStyle(Brand.muted)
                if let holiday = skippedHoliday(now: now) {
                    Text(L("home_holiday_skipped", Format.shortDay(holiday))).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.muted)
                }
                HeroWave().frame(height: 72).padding(.top, 14)
            } else {
                Text(L("home_no_alarm_title")).font(Fonts.manrope(28, .bold, relativeTo: .title))
                Text(L("home_no_alarm_text")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.muted)
                HeroWave(windowFraction: 0.3).frame(height: 60).padding(.vertical, 12)
                Button { creating = true } label: {
                    Label(L("home_add_alarm"), systemImage: "plus").font(Fonts.manrope(15, .bold, relativeTo: .headline))
                }
                .buttonStyle(.borderedProminent)
                .tint(Brand.wave)
                .foregroundStyle(Brand.nightTop)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .foregroundStyle(Brand.moon)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 22)
        .padding(.top, 70)
        .padding(.bottom, 24)
        .background {
            if scheme != .dark {
                NightSky().clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 32, bottomTrailingRadius: 32))
            }
        }
        .onTapGesture { if let plan { editing = model.alarms.first { $0.id == plan.alarmId } } }
        // Mit VoiceOver: Doppeltipp öffnet den Wecker wie ein Tipp.
        .accessibilityAddTraits(plan != nil ? .isButton : [])
        .accessibilityAction { if let plan { editing = model.alarms.first { $0.id == plan.alarmId } } }
    }

    /// Feiertag, an dem ein Wecker sonst geweckt hätte, bis zum nächsten Wecken («Do 30.10.: Feiertag – nicht geweckt»).
    private func skippedHoliday(now: Date) -> String? {
        AlarmSchedule.holidayNote(model.alarms, now: now, doneUntil: model.doneUntil, calendar: .current, holidays: model.holidays.dates)
    }

    private func greetingKey(_ now: Date) -> String {
        switch Calendar.current.component(.hour, from: now) {
        case 5...10: "greeting_morning"
        case 11...16: "greeting_day"
        case 17...21: "greeting_evening"
        default: "greeting_night"
        }
    }
}

/// Karte auf dem Hintergrund: dunkel Weiss mit 6 %, hell weiss; Radius 22 wie auf Android.
struct CardBackground: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Brand.card(scheme), in: RoundedRectangle(cornerRadius: Brand.cardRadius))
            .padding(.horizontal, 16)
    }
}

extension View {
    func card() -> some View { modifier(CardBackground()) }
}

private struct NightstandButton: View {
    let action: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Label(L("nightstand_start"), systemImage: "sunrise.fill").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Text(L("nightstand_hint")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme)).multilineTextAlignment(.leading)
            }
        }
        .buttonStyle(.plain)
        .card()
    }
}

private struct LastNightCard: View {
    let night: NightRecord
    let now: Date
    let hint: MissHint?
    let onFeeling: (Int) -> Void
    @Environment(\.colorScheme) private var scheme

    /// So lange nach dem Wecken gilt die letzte Nacht als «Dein Morgen».
    private static let morning: TimeInterval = 12 * 3600

    var body: some View {
        let isMorning = now.timeIntervalSince(night.wokeAt) < Self.morning
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L(isMorning ? "home_morning" : "home_last_night")).font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(night.wokeAt)).font(Fonts.manrope(15, .bold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30), unmeasured: unmeasuredEpochs(night)).frame(height: 46)
            // Erklärung (14, leise), darunter die Zusatzinfos (12, noch leiser).
            Text(nightSummary(night)).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme))
            Text(nightDetails(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
            if let unmeasured = unmeasuredText(night) {
                Text(unmeasured).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
            }
            if let hint {
                VStack(alignment: .leading, spacing: 2) {
                    Text(LP("hint_title", MissHint.nights, MissHint.nights)).font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                    Text(L(hint == .gaps ? "hint_gaps" : hint == .heartMissing ? "hint_heart" : "hint_calm"))
                        .font(Fonts.manrope(12, relativeTo: .caption))
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .padding(.top, 6)
            }
            if isMorning && night.feeling == nil {
                FeelingPicker(onPick: onFeeling).padding(.top, 8)
            }
            if isMorning {
                NightNotesButton(nightDate: SleepNotes.nightDate(of: night, calendar: .current),
                                 title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
                    .padding(.top, 4)
                NightSoundsButton(night: SleepNotes.nightDate(of: night, calendar: .current),
                                  title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
            }
        }
        .card()
    }
}

private struct AlarmRow: View {
    @Environment(PhoneModel.self) private var model
    let alarm: AlarmSpec
    let onTap: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(alarm.sleepDurationMinutes.map { L("alarm_after_sleep", Format.duration(minutes: $0)) } ?? Format.window(alarm))
                    .font(Fonts.manrope(22, .bold, relativeTo: .title2))
                    .foregroundStyle(alarm.enabled ? .primary : .secondary)
                Text(details).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                if let skipped {
                    HStack(spacing: 8) {
                        Text(L("alarm_skipped", Format.shortDay(skipped))).font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                        Button(L("alarm_skip_undo")) { model.setSkipped(alarm, false) }
                            .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                            .buttonStyle(.borderless)
                            .accessibilityLabel(undoLabel(skipped))
                    }
                }
            }
            Spacer()
            Toggle("", isOn: Binding(get: { alarm.enabled }, set: { model.setEnabled(alarm, $0) }))
                .labelsHidden()
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .contextMenu {
            Button(L("editor_edit"), systemImage: "pencil", action: onTap)
            if skipped != nil {
                Button(L("alarm_skip_undo"), systemImage: "arrow.uturn.backward") { model.setSkipped(alarm, false) }
            } else if !alarm.isOneShot && alarm.enabled && model.watchKnowsSkip {
                Button(skipTitle, systemImage: "forward.end") { model.setSkipped(alarm, true) }
            }
        }
        .accessibilityAction(named: L("editor_edit"), onTap)
        .accessibilityActions {
            if let skipped {
                Button(undoLabel(skipped)) { model.setSkipped(alarm, false) }
            } else if !alarm.isOneShot && alarm.enabled && model.watchKnowsSkip {
                Button(skipTitle) { model.setSkipped(alarm, true) }
            }
        }
        .card()
        .accessibilityElement(children: .combine)
    }

    /// Übersprungener Termin «yyyy-MM-dd», solange er bevorsteht.
    private var skipped: String? { AlarmSchedule.skippedDate(alarm, now: Date(), calendar: .current) }

    /// «Do 30.10. überspringen»: der Tag, den der Menüpunkt trifft (nach früh geweckter Nacht der nächste).
    private var skipTitle: String {
        AlarmSchedule.nextSkipDate(alarm, now: Date(), doneUntil: model.doneUntil[alarm.id], calendar: .current,
                                   holidays: model.holidays.dates)
            .map { L("alarm_skip_on", Format.shortDay($0)) } ?? L("alarm_skip")
    }

    /// Für Bildschirmleser: «Überspringen rückgängig: Übersprungen: Do 30.10. (6:30–7:00)».
    private func undoLabel(_ skipped: String) -> String {
        L("alarm_skip_undo_for", L("alarm_skipped", Format.shortDay(skipped)) + " (" + Format.window(alarm) + ")")
    }

    private var details: String {
        var parts = [Format.days(alarm)]
        if alarm.sleepDurationMinutes != nil { parts.append(L("alarm_latest", Format.time(hour: alarm.hour, minute: alarm.minute))) }
        if !alarm.label.isEmpty { parts.append(alarm.label) }
        return parts.joined(separator: " · ")
    }
}

