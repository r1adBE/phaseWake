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
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ZStack {
                if scheme == .dark { NightSky() }
                ScrollView {
                    VStack(spacing: 12) {
                        TimelineView(.everyMinute) { context in
                            header(now: context.date)
                        }
                        TimelineView(.everyMinute) { context in
                            let plan = AlarmSchedule.earliestPlan(model.alarms, now: context.date, calendar: .current)
                            VStack(spacing: 12) {
                                // Nacht-Check nur, wenn ein Wecker ansteht.
                                if plan != nil {
                                    NightCheckCard(failed: failedChecks) { check in
                                        Task { await NightCheck.fix(check); failedChecks = await NightCheck.collect() }
                                    }
                                }
                                if let plan, model.alarms.first(where: { $0.id == plan.alarmId })?.sunrise != .off {
                                    NightstandButton { nightstand = true }
                                }
                                if let night = model.nights.last {
                                    LastNightCard(
                                        night: night,
                                        now: context.date,
                                        hint: MissHint.of(model.alarms.first { $0.id == night.alarmId }, nights: model.nights)
                                    ) { model.setFeeling($0, for: night) }
                                }
                            }
                        }
                        if !model.alarms.isEmpty {
                            Text(L("home_alarms"))
                                .font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 20)
                                .padding(.top, 8)
                            ForEach(model.alarms) { alarm in
                                AlarmRow(alarm: alarm) { editing = alarm }
                            }
                        }
                        Spacer(minLength: 80)
                    }
                }
                .ignoresSafeArea(edges: .top)
            }
            .background(scheme == .dark ? Color.clear : Color(hex: 0xF4F6FB))
            .overlay(alignment: .bottomTrailing) {
                if !model.alarms.isEmpty {
                    Button { creating = true } label: {
                        Label(L("home_add_alarm"), systemImage: "plus")
                            .font(Fonts.manrope(15, .bold, relativeTo: .headline))
                            .padding(.horizontal, 20).padding(.vertical, 16)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(scheme == .dark ? Brand.echo : Color(hex: 0xDCE4FF))
                    .foregroundStyle(scheme == .dark ? .white : Brand.nightTop)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .padding(16)
                }
            }
            .task(id: scenePhase) {
                // Bei jedem Öffnen und danach jede halbe Minute prüfen.
                while scenePhase == .active, !Task.isCancelled {
                    failedChecks = await NightCheck.collect()
                    try? await Task.sleep(for: .seconds(30))
                }
            }
            .fullScreenCover(isPresented: $nightstand) { NightstandView() }
            .sheet(item: $editing) { EditorView(existing: $0) }
            .sheet(isPresented: $creating) { EditorView(existing: nil) }
        }
    }

    private func header(now: Date) -> some View {
        let plan = AlarmSchedule.earliestPlan(model.alarms, now: now, calendar: .current)
        return VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 10) {
                LogoMark().frame(width: 34, height: 34)
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
                HeroWave().frame(height: 72).padding(.top, 14)
            } else {
                Text(L("home_no_alarm_title")).font(Fonts.manrope(28, .bold, relativeTo: .title))
                Text(L("home_no_alarm_text")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(Brand.muted)
                HeroWave(windowFraction: 0.3).frame(height: 60).padding(.vertical, 12)
                Button { creating = true } label: {
                    Label(L("home_add_alarm"), systemImage: "plus").font(Fonts.manrope(15, .bold, relativeTo: .headline))
                }
                .buttonStyle(.borderedProminent)
                .tint(.white)
                .foregroundStyle(Brand.nightBottom)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .foregroundStyle(.white)
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

    private func greetingKey(_ now: Date) -> String {
        switch Calendar.current.component(.hour, from: now) {
        case 5...10: "greeting_morning"
        case 11...16: "greeting_day"
        case 17...21: "greeting_evening"
        default: "greeting_night"
        }
    }
}

/// Karte auf dem Hintergrund: dunkel durchscheinend, hell weiss.
struct CardBackground: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(scheme == .dark ? Color.white.opacity(0.07) : Color.white, in: RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 16)
    }
}

extension View {
    func card() -> some View { modifier(CardBackground()) }
}

private struct NightstandButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Label(L("nightstand_start"), systemImage: "sunrise.fill").font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Text(L("nightstand_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary).multilineTextAlignment(.leading)
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

    /// So lange nach dem Wecken gilt die letzte Nacht als «Dein Morgen».
    private static let morning: TimeInterval = 12 * 3600

    var body: some View {
        let isMorning = now.timeIntervalSince(night.wokeAt) < Self.morning
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(L(isMorning ? "home_morning" : "home_last_night")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(night.wokeAt)).font(Fonts.manrope(15, .bold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            NightBars(epochs: night.epochs, totalEpochs: Int(night.target.timeIntervalSince(night.windowStart) / 30)).frame(height: 46)
            Text(nightSummary(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            Text(nightDetails(night)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
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
            }
            Spacer()
            Toggle("", isOn: Binding(get: { alarm.enabled }, set: { model.setEnabled(alarm, $0) }))
                .labelsHidden()
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .accessibilityAction(named: L("editor_edit"), onTap)
        .card()
        .accessibilityElement(children: .combine)
    }

    private var details: String {
        var parts = [Format.days(alarm)]
        if alarm.sleepDurationMinutes != nil { parts.append(L("alarm_latest", Format.time(hour: alarm.hour, minute: alarm.minute))) }
        if !alarm.label.isEmpty { parts.append(alarm.label) }
        return parts.joined(separator: " · ")
    }
}

