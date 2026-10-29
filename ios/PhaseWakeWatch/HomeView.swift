import PhaseWakeCore
import SwiftUI

/// Startbildschirm der Uhr: nächstes Weckfenster als Ring, Wecker ein/aus.
struct HomeView: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    /// Wecker im Editor: ein bestehender oder ein neuer (dann noch nicht in der Liste).
    @State private var editing: AlarmSpec?
    @State private var choosingNap = false
    @State private var diagUnlocked = DiagStore.shared.unlocked

    var body: some View {
        NavigationStack {
            List {
                NextWindow(plan: model.plan, alarm: model.alarms.first { $0.id == model.plan?.alarmId }, sleepGoal: model.sleepGoal)
                    .listRowBackground(Color.clear)
                if case .waiting = SmartAlarm.shared.phase {
                    // Nicht scharf: watchOS plant nur bei offener App und höchstens 36 h im Voraus.
                    Text(L("watch_not_armed"))
                        .font(Fonts.manrope(12, relativeTo: .footnote))
                        .foregroundStyle(Brand.muted)
                        .listRowBackground(Color.clear)
                }
                WatchNapRow(choosing: $choosingNap)
                if let night = model.storage.nights().last {
                    // Letzte Nacht: wann und warum geweckt wurde.
                    VStack(spacing: 1) {
                        Text(L("home_last_night")).font(Fonts.manrope(11, relativeTo: .caption2)).foregroundStyle(Brand.muted)
                        Text(Format.time(night.wokeAt)).font(Fonts.manrope(17, .bold, relativeTo: .headline))
                        Text(L(ringReasonKey(night.reason)))
                            .font(Fonts.manrope(11, relativeTo: .caption2))
                            .foregroundStyle(Brand.muted)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .accessibilityElement(children: .combine)
                    .listRowBackground(Color.clear)
                }
                if !model.alarms.isEmpty {
                    Section(L("home_alarms")) {
                        ForEach(model.alarms) { alarm in
                            // Zeile antippen öffnet den Editor, der Schalter schaltet ein/aus.
                            HStack {
                                Button { editing = alarm } label: {
                                    VStack(alignment: .leading) {
                                        Text(Format.window(alarm)).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                                        Text([Format.days(alarm), alarm.label].filter { !$0.isEmpty }.joined(separator: " · "))
                                            .font(Fonts.manrope(12, relativeTo: .footnote))
                                            .foregroundStyle(Brand.muted)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityHint(L("editor_edit"))
                                Toggle(Format.window(alarm), isOn: Binding(get: { alarm.enabled }, set: { model.setEnabled(alarm, $0) }))
                                    .labelsHidden()
                                    .tint(Brand.echo)
                                    .fixedSize()
                            }
                        }
                    }
                }
                if model.canAddAlarm {
                    Button { editing = AlarmSpec.newOnWatch() } label: {
                        Label(L("home_add_alarm"), systemImage: "plus")
                            .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    }
                } else {
                    Text(L("home_max_alarms", Int64(WatchModel.maxAlarms)))
                        .font(Fonts.manrope(12, relativeTo: .footnote))
                        .foregroundStyle(Brand.muted)
                        .listRowBackground(Color.clear)
                }
                if diagUnlocked {
                    NavigationLink(L("diag_title")) { WatchDiagView(unlocked: $diagUnlocked) }
                }
                WatchVersionRow(unlocked: $diagUnlocked).listRowBackground(Color.clear)
            }
            .background(NightSky(strength: 0.55))
            .scrollContentBackground(.hidden)
        }
        .sheet(isPresented: $choosingNap) {
            NavigationStack { WatchNapView() }.environment(model)
        }
        .sheet(item: $editing) { alarm in
            WatchEditorView(start: alarm, isNew: !model.alarms.contains { $0.id == alarm.id })
                .environment(model)
        }
        .onChange(of: scenePhase) { _, phase in
            // Nur bei aktiver App lässt sich die Smart-Alarm-Sitzung planen.
            // Dabei auch den Akkustand ans iPhone (Nacht-Check warnt unter 20 %).
            if phase == .active {
                model.reschedule()
                WatchConnectivityBridge.shared.publishBattery()
            }
        }
        .onAppear {
            model.reschedule()
            WatchConnectivityBridge.shared.publishBattery()
        }
        .task { await SmartAlarm.shared.requestAccess() }
    }
}

private struct NextWindow: View {
    let plan: WakePlan?
    let alarm: AlarmSpec?
    /// Schlafziel in Minuten für den Einschlafpunkt.
    let sleepGoal: Int

    /// Einschlafpunkt nur zu einem gespeicherten Wecker, nicht zu Powernap oder Probewecker.
    private var bedtime: Date? {
        guard let plan, alarm != nil else { return nil }
        return Bedtime.onDial(nextAlarmTarget: plan.target, goalMinutes: sleepGoal)
    }

    var body: some View {
        ZStack {
            if let plan {
                // Zeiger «jetzt», einmal pro Minute neu.
                TimelineView(.everyMinute) { context in
                    // «Genau»: kein Bogen, nur der Weckpunkt (die Uhr misst davor nur für den Verlauf).
                    WindowRing(start: dial(plan.windowStart), end: dial(plan.target), now: dial(context.date),
                               bedtime: bedtime.map { dial($0) }, exact: plan.exact)
                }
            }
            VStack(spacing: 2) {
                if let plan {
                    Text(Format.day(plan.target, todayKey: "home_today", tomorrowKey: "home_tomorrow"))
                        .font(Fonts.manrope(12, relativeTo: .caption))
                        .foregroundStyle(Brand.muted)
                    Text(Format.window(start: plan.windowStart, target: plan.target, exact: plan.exact, separator: "–"))
                        .font(Fonts.manrope(20, .bold, relativeTo: .title3))
                        .minimumScaleFactor(0.7)
                        .lineLimit(1)
                    if !plan.exact {
                        Text(L("home_window_length", Int64(alarm?.windowMinutes ?? AlarmSpec.defaultWindowMinutes)))
                            .font(Fonts.manrope(11, relativeTo: .caption2))
                            .foregroundStyle(Brand.muted)
                    }
                } else {
                    Text(L("home_no_alarm")).font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    Text(L("home_no_alarm_hint"))
                        .font(Fonts.manrope(12, relativeTo: .caption))
                        .foregroundStyle(Brand.muted)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 18)
            .accessibilityElement(children: .combine)
            // Der Ring ist für VoiceOver verborgen: die Bettzeit nur hier, sichtbar bleibt sie allein als feiner Kreis.
            .accessibilityValue(bedtime.map { L("chart_bedtime", Format.time($0)) } ?? "")
        }
        .frame(height: 160)
        .frame(maxWidth: .infinity)
    }

    /// Position auf dem 12-Stunden-Zifferblatt (0 = 12 Uhr).
    private func dial(_ date: Date) -> Double {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return Double((c.hour ?? 0) % 12 * 60 + (c.minute ?? 0)) / 720
    }
}

/// Ring als 12-Stunden-Zifferblatt: feine Marken für 12, 3, 6 und 9 Uhr, das Weckfenster als Bogen mit dem
/// Weckpunkt (runder Punkt) am Ende, ein kurzer Zeiger für «jetzt» (Strich quer zur Spur) und der Einschlafpunkt
/// (Weckzeit minus Schlafziel) als feiner hohler Kreis. Drei Formen, damit nicht die Farbe allein unterscheidet.
/// Positionen 0–1, 0 = 12 Uhr; ohne `bedtime` kein Einschlafpunkt; bei `exact` («Genau») kein Bogen, nur der Weckpunkt.
private struct WindowRing: View {
    let start: Double
    let end: Double
    let now: Double
    let bedtime: Double?
    var exact = false

    var body: some View {
        let sweep = max(0.01, (end - start + 1).truncatingRemainder(dividingBy: 1))
        GeometryReader { geo in
            let r = min(geo.size.width, geo.size.height) / 2
            ZStack {
                Circle().stroke(Brand.track, lineWidth: 5)
                ForEach(0..<4, id: \.self) { k in
                    Capsule()
                        .fill(Brand.muted)
                        .frame(width: 1.5, height: k == 0 ? 8 : 5)
                        .offset(y: -(r - 4.5 - (k == 0 ? 4 : 2.5)))
                        .rotationEffect(.degrees(Double(k) * 90))
                }
                if !exact {
                    Circle()
                        .trim(from: 0, to: sweep)
                        .stroke(Brand.wave, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .rotationEffect(.degrees(start * 360 - 90))
                }
                if let bedtime {
                    // Hohl, damit die Spur durchscheint; `muted` hat auf Spur und Nachthimmel über 8 : 1 Kontrast.
                    Circle()
                        .stroke(Brand.muted, lineWidth: 1.5)
                        .frame(width: 8, height: 8)
                        .offset(y: -r)
                        .rotationEffect(.degrees(bedtime * 360))
                }
                Capsule()
                    .fill(Brand.moon)
                    .frame(width: 2.5, height: 13)
                    .offset(y: -r)
                    .rotationEffect(.degrees(now * 360))
                Circle()
                    .fill(Brand.moon)
                    .frame(width: 8, height: 8)
                    .offset(y: -r)
                    .rotationEffect(.degrees(end * 360))
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .padding(4)
        .accessibilityHidden(true)
    }
}
