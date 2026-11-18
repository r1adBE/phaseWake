import PhaseWakeCore
import SwiftUI

/// Startseite: Kopf im Himmel mit dem nächsten Fenster, letzte Nacht, alle Wecker.
/// Dunkel ist die ganze Seite Nachthimmel, hell trägt der Kopf den Tageshimmel «Creme Caramel».
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
    /// Ø Schlaf und Schlafziel der letzten 7 Nächte für «Letzte Nacht» (``HomeSleepWeek``); `nil`: keine Zeile.
    @State private var sleepWeek: SleepWeek?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ZStack {
                if scheme == .dark {
                    TimelineView(.everyMinute) { c in
                        NightSky(mood: .at(now: c.date, plan: model.nextPlan(now: c.date)))
                    }
                }
                ScrollView {
                    VStack(spacing: 16) {
                        TimelineView(.everyMinute) { context in
                            header(now: context.date)
                        }
                        TimelineView(.everyMinute) { context in
                            cards(now: context.date)
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
                // Bei jedem Öffnen und danach jede halbe Minute prüfen; die Schlafdauer fürs Widget nur beim Öffnen.
                var opened = true
                while scenePhase == .active, !Task.isCancelled {
                    failedChecks = await NightCheck.collect()
                    PhoneWidgetSync.publish(model, failed: failedChecks)
                    if opened {
                        opened = false
                        await PhoneWidgetSync.refreshAsleep(model)
                        sleepWeek = await HomeSleepWeek.load(model)
                    }
                    // «Geräusche aufnehmen»: ab der Bettzeit (Vorgabe) bzw. am Abend (selbst eingeschaltet) von selbst
                    // starten.
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

    /// Karten unter dem Kopf: Nacht-Check, Wecker, Abendkarten, letzte Nacht und Morgenbericht.
    @ViewBuilder private func cards(now: Date) -> some View {
        // Eine früh geweckte Nacht zählt nicht mehr (wie Nacht-Check und Widget).
        let plan = model.nextPlan(now: now)
        // Am Morgen (nach dem Wecken bis 12 Uhr) steht die letzte Nacht zuoberst.
        let morningFirst = model.nights.last.map { LastNightCard.isMorningFirst($0, now: now) } ?? false
        VStack(spacing: 16) {
            if morningFirst { lastNight(now: now, morningFirst: true) }
            // Nacht-Check nur, wenn ein Wecker ansteht.
            if plan != nil {
                NightCheckCard(failed: failedChecks) { check in
                    Task { await NightCheck.fix(check); failedChecks = await NightCheck.collect() }
                }
            }
            BackupSnoozeCard(now: now)
            TransientStatusCard()
            // «Deine Wecker» gleich nach dem Nacht-Check, vor den Abendkarten; wie Android.
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
            if model.transient.nap == nil { NapCard() }
            if let plan, model.alarms.first(where: { $0.id == plan.alarmId })?.sunrise != .off {
                NightstandButton { nightstand = true }
            }
            // Abends bis zum Weckfenster: Notizen für die kommende Nacht.
            let next = model.nextPlan(now: now)
            let evening = SleepNotes.entryVisible(now: now,
                                                  nextWindowStart: next?.windowStart,
                                                  calendar: .gregorianLocal)
            let tonight = SleepNotes.nightDate(now, calendar: .gregorianLocal)
            if evening {
                BedtimeAdviceCard(now: now)
                TonightNotesCard(nightDate: tonight)
                RoutineCard(night: tonight)
            }
            // Geräusche: abends zum Einschalten, und solange die Aufnahme läuft.
            if evening || model.recorder.running {
                SoundRecordingCard(night: tonight)
            }
            SoundsButton { sounds = true }
            LearningHomeCard()
            RecordCard()
            // Höchstens eine Auffälligkeit, die wichtigste; neben der Bettzeit-Karte ohne denselben
            // Tipp (wie Android).
            let adviceShown = evening
                && BedtimeAdviceCard.range(model: model, values: PhoneLearning.shared.state.values, now: now) != nil
            FindingHomeCard(bedtimeAdvice: adviceShown)
            if !morningFirst { lastNight(now: now, morningFirst: false) }
            // Morgenbericht ohne aufgezeichnete Nacht (z. B. Uhr nicht getragen): nur die Geräusche
            // – nicht während der Aufnahme und nicht für die Nacht, die gerade läuft.
            let recorded = Set(model.nights.last.map {
                [SleepNotes.nightDate(of: $0, calendar: .gregorianLocal)]
            } ?? [])
            if let sounds = SoundEvents.nightsWithoutRecord(model.recorder.clips,
                                                            recorded: recorded).first,
               now.timeIntervalSince(sounds.last) < 12 * 3600, !model.recorder.running,
               !(evening && tonight == sounds.night) {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("home_morning"))
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    NightSoundsButton(night: sounds.night,
                                      title: sounds.last.formatted(date: .abbreviated,
                                                                   time: .omitted))
                }
                .card()
            }
        }
    }

    /// Karte der letzten Nacht; `morningFirst` = am Morgen zuoberst mit «Geweckt um 6:42» gross.
    @ViewBuilder private func lastNight(now: Date, morningFirst: Bool) -> some View {
        if let night = model.nights.last {
            LastNightCard(
                night: night,
                now: now,
                // Ungemessene erste Minuten der Apple Watch zählen nicht als Lücke.
                hint: MissHint.of(model.alarms.first { $0.id == night.alarmId },
                                  nights: model.nights.map(measuredPart)),
                morningFirst: morningFirst,
                sleepWeek: sleepWeek
            ) { model.setFeeling($0, for: night) }
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
        // Dunkel im Nachthimmel helle Schrift, hell im Tageshimmel dunkle (wie Android `skyInk`).
        let ink = SkyInk.of(scheme)
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 10) {
                LogoMark(moon: ink.moon, wave: ink.wave).frame(width: 48, height: 48)
                Text("Phase Wake").font(Fonts.manrope(20, .bold, relativeTo: .title3))
            }
            .padding(.bottom, 26)
            Text(L(greetingKey(now)))
                .font(Fonts.manrope(15, .semibold, relativeTo: .subheadline)).foregroundStyle(ink.muted)
            if let plan, let alarm = model.alarms.first(where: { $0.id == plan.alarmId }) {
                // Grosser Countdown-Bogen mit dem Weckfenster darin (wie Android).
                HeroPlan(plan: plan, alarm: alarm, now: now)
                if let holiday = skippedHoliday(now: now) {
                    Text(L("home_holiday_skipped", Format.shortDay(holiday)))
                        .font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(ink.muted)
                }
            } else {
                Text(L("home_no_alarm_title")).font(Fonts.manrope(28, .bold, relativeTo: .title))
                Text(L("home_no_alarm_text"))
                    .font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(ink.muted)
                HeroWave(windowFraction: 0.3).frame(height: 60).padding(.vertical, 12)
                Button { creating = true } label: {
                    Label(L("home_add_alarm"), systemImage: "plus")
                        .font(Fonts.manrope(15, .bold, relativeTo: .headline))
                }
                .buttonStyle(.borderedProminent)
                .tint(ink.button)
                .foregroundStyle(ink.onButton)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .foregroundStyle(ink.text)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 22)
        .padding(.top, 70)
        .padding(.bottom, 24)
        .background {
            if scheme != .dark {
                DaySky(mood: .at(now: now, plan: plan))
                    .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: 32, bottomTrailingRadius: 32))
            }
        }
        .onTapGesture { if let plan { editing = model.alarms.first { $0.id == plan.alarmId } } }
        // Mit VoiceOver: Doppeltipp öffnet den Wecker wie ein Tipp.
        .accessibilityAddTraits(plan != nil ? .isButton : [])
        .accessibilityAction { if let plan { editing = model.alarms.first { $0.id == plan.alarmId } } }
    }

    /// Feiertag, an dem ein Wecker sonst geweckt hätte, bis zum nächsten Wecken («Do 30.10.: Feiertag – nicht
    /// geweckt»).
    private func skippedHoliday(now: Date) -> String? {
        AlarmSchedule.holidayNote(model.alarms, now: now, doneUntil: model.doneUntil, calendar: .gregorianLocal,
                                  holidays: model.holidays.dates)
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

/// Karte auf dem Hintergrund: dunkel Weiss mit 6 % (auf den übrigen Seiten das dichtere Glas ``Brand/panel(_:)``),
/// hell weiss; Radius 22 wie auf Android. `tinted`: Kennzahl mit einem Hauch der Hauptfarbe oben (wie `tileTint`).
struct CardBackground: ViewModifier {
    var tinted = false
    /// Innenabstand; 0 für Karten aus Zeilen mit eigenem Abstand (Liste der Wochen im Verlauf).
    var inset: CGFloat = 16
    @Environment(\.colorScheme) private var scheme
    @Environment(\.skyPanel) private var panel
    func body(content: Content) -> some View {
        content
            .padding(inset)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                if tinted {
                    LinearGradient(colors: [Brand.accent(scheme).opacity(scheme == .dark ? 0.18 : 0.10), .clear],
                                   startPoint: .top, endPoint: .bottom)
                        .clipShape(RoundedRectangle(cornerRadius: Brand.cardRadius))
                }
            }
            .background(panel ? Brand.panel(scheme) : Brand.card(scheme),
                        in: RoundedRectangle(cornerRadius: Brand.cardRadius)).overlay(CardEdge())
            .shadow(color: scheme == .dark ? .clear : .black.opacity(0.10), radius: 6, y: 2)
            .padding(.horizontal, 16)
    }
}

extension View {
    func card(tinted: Bool = false, inset: CGFloat = 16) -> some View {
        modifier(CardBackground(tinted: tinted, inset: inset))
    }
}

private struct LastNightCard: View {
    let night: NightRecord
    let now: Date
    let hint: MissHint?
    /// Am Morgen zuoberst (`isMorningFirst`): unter dem Titel «Geweckt um 6:42» gross statt nur der Uhrzeit.
    var morningFirst = false
    /// Ø Schlaf und Schlafziel der letzten 7 Nächte (``HomeSleepWeek``); `nil`: keine Zeile.
    var sleepWeek: SleepWeek?
    let onFeeling: (Int) -> Void
    @Environment(\.colorScheme) private var scheme

    /// So lange nach dem Wecken gilt die letzte Nacht als «Dein Morgen».
    private static let morning: TimeInterval = 12 * 3600
    /// Bis zu dieser Stunde (Ortszeit) steht die Karte am Weckmorgen zuoberst.
    private static let morningEndHour = 12

    /// Ob die Nacht heute geweckt hat und es noch vor 12 Uhr ist: Dann steht die Karte zuoberst (wie Android).
    static func isMorningFirst(_ night: NightRecord, now: Date, calendar: Calendar = .current) -> Bool {
        now >= night.wokeAt && calendar.isDate(night.wokeAt, inSameDayAs: now)
            && calendar.component(.hour, from: now) < morningEndHour
    }

    var body: some View {
        let isMorning = now.timeIntervalSince(night.wokeAt) < Self.morning
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L(isMorning ? "home_morning" : "home_last_night"))
                    .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Spacer()
                if !morningFirst {
                    Text(Format.time(night.wokeAt))
                        .font(Fonts.manrope(15, .bold, relativeTo: .headline)).foregroundStyle(.tint)
                }
            }
            // Wie Android: nur «Geweckt um 6:42» gross; der Grund steht einmal, in der Erklärung darunter.
            if morningFirst {
                Text(L("night_woke", Format.time(night.wokeAt)))
                    .font(Fonts.manrope(22, .bold, relativeTo: .title2))
                    .foregroundStyle(.tint)
            }
            NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30),
                      unmeasured: unmeasuredEpochs(night)).frame(height: 46)
            // Erklärung (14, leise), darunter die Zusatzinfos (12, noch leiser).
            Text(nightSummary(night))
                .font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.secondaryText(scheme))
            Text(nightDetails(night))
                .font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
            if let unmeasured = unmeasuredText(night) {
                Text(unmeasured)
                    .font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
            }
            if let sleepWeek {
                Text(HomeSleepWeek.text(sleepWeek))
                    .font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.secondaryText(scheme))
                    .padding(.top, 2)
            }
            if let hint {
                VStack(alignment: .leading, spacing: 2) {
                    Text(LP("hint_title", MissHint.nights, MissHint.nights))
                        .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
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
                NightNotesButton(nightDate: SleepNotes.nightDate(of: night, calendar: .gregorianLocal),
                                 title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
                    .padding(.top, 4)
                NightSoundsButton(night: SleepNotes.nightDate(of: night, calendar: .gregorianLocal),
                                  title: night.wokeAt.formatted(date: .abbreviated, time: .omitted))
            }
        }
        .card()
    }
}
