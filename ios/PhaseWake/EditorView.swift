import PhaseWakeCore
import SwiftUI

/// Wecker anlegen oder bearbeiten.
struct EditorView: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let existing: AlarmSpec?

    @State private var time = Date()
    @State private var window = Double(AlarmSpec.defaultWindowMinutes)
    @State private var days: Set<Weekday> = [.monday, .tuesday, .wednesday, .thursday, .friday]
    @State private var phone = PhoneAlert.sound
    @State private var watchSound = false
    @State private var label = ""
    /// Weckton: «raw:wake_…» (eigener Ton) oder leer bzw. anderer Wert = Standardton des Systems.
    @State private var sound = ""
    @State private var preview = WakeTonePreview()
    @State private var sunrise = Sunrise.off
    @State private var original: AlarmSpec?
    @State private var confirmDiscard = false
    @State private var confirmDelete = false
    @State private var newId = UUID().uuidString
    @State private var showMore = false
    @State private var skipHolidays = false
    /// Einschlaf-Modus («nach 7½ Std.»): Schlafdauer statt fester Zielzeit; die Uhrzeit ist dann die späteste Weckzeit.
    @State private var sleepMode = false
    @State private var sleepMinutes = Double(EditorView.defaultSleepMinutes)
    /// Anderswo gelöscht bzw. kein Platz mehr (`EditorSave`): Hinweis.
    @State private var gone = false
    @State private var full = false
    /// Selbst gelöscht: Das Verschwinden aus der Liste ist dann kein Löschen anderswo.
    @State private var deleting = false

    /// Vorgabe und Bereich der Schlafdauer wie Android (`EditorState`, `SLEEP_MIN`/`SLEEP_MAX`), in Schritten von 15
    /// Min.
    private static let defaultSleepMinutes = 450
    private static let sleepRange = 180.0...720.0

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    modeSections
                    adviceSection
                    windowSection
                    daysSection
                    alertSection
                    moreSection
                    deleteSection
                }
                .skyRows()
            }
            .skyPage()
            .navigationTitle(L(existing == nil ? "editor_new" : "editor_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Bei Änderungen zuerst fragen; Wischen zum Schliessen ist dann gesperrt.
                    Button(L("action_cancel")) { if dirty { confirmDiscard = true } else { dismiss() } }
                        .confirmationDialog(L("editor_discard_title"), isPresented: $confirmDiscard,
                                            titleVisibility: .visible) {
                            Button(L("editor_discard"), role: .destructive) { dismiss() }
                            Button(L("editor_keep_editing"), role: .cancel) {}
                        }
                }
                ToolbarItem(placement: .confirmationAction) { Button(L("editor_save")) { save() }.bold() }
            }
            .onAppear(perform: load)
            .onDisappear { preview.stop() }
            .interactiveDismissDisabled(dirty)
            // Wecker anderswo gelöscht (Uhr), während er hier offen ist: Hinweis und zu, statt ihn neu anzulegen.
            .onChange(of: model.alarms.map(\.id)) { _, ids in
                if check(ids) == .gone, !deleting { gone = true }
            }
            .alert(L("editor_alarm_gone"), isPresented: $gone) {
                Button(L("test_close")) { dismiss() }
            }
            .alert(L("home_max_alarms", Int64(PhoneModel.maxAlarms)), isPresented: $full) {
                Button(L("editor_keep_editing"), role: .cancel) {}
            }
        }
    }

    /// Modus (Uhrzeit oder Schlafdauer), Uhrzeit und Schlafdauer.
    @ViewBuilder
    private var modeSections: some View {
        // «Uhrzeit · Schlafdauer» wie Android. Einschlaf-Modus auf der Apple Watch: «Gute Nacht» legt die
        // Zielzeit aus der gelernten Einschlafdauer fest (Näherung, `GoodNightState`); ohne Tippen gilt die
        // Uhrzeit.
        Section {
            Picker(L("editor_mode_sleep"), selection: $sleepMode) {
                Text(L("editor_mode_clock")).tag(false)
                Text(L("editor_mode_sleep")).tag(true)
            }
            .pickerStyle(.segmented)
            .controlSize(.large)
        }

        Section(L(sleepMode ? "editor_latest" : "editor_target")) {
            DatePicker("", selection: $time, displayedComponents: .hourAndMinute)
                .datePickerStyle(.wheel)
                .labelsHidden()
                .frame(maxWidth: .infinity)
        }

        if sleepMode {
            Section {
                Text(L("editor_sleep_duration", Format.duration(minutes: Int(sleepMinutes))))
                    .font(.headline)
                Slider(value: $sleepMinutes, in: Self.sleepRange, step: 15)
                    .accessibilityValue(Format.duration(minutes: Int(sleepMinutes)))
                Text(L("editor_sleep_hint_apple")).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    /// Vorschlag für ein grösseres Fenster aus den bisherigen Nächten.
    @ViewBuilder
    private var adviceSection: some View {
        // «Genau» ist bewusst gewählt: dann kein Vorschlag für ein grösseres Fenster.
        if let existing, Int(window) != AlarmSpec.exactWindow,
           let advice = WindowAdvice.suggest(existing, history: model.nights),
           Int(window) < advice.windowMinutes {
            Section {
                Text(L("editor_advice_title")).font(.headline)
                Text(LP("editor_advice_text", advice.nights, advice.deadlineNights, advice.nights,
                        advice.windowMinutes)).font(.footnote)
                Button(L("editor_advice_apply", advice.windowMinutes)) {
                    window = Double(advice.windowMinutes)
                }
            }
        }
    }

    /// Weckfenster mit Hinweis auf seinen Beginn.
    @ViewBuilder
    private var windowSection: some View {
        Section {
            // «Genau · 10 · 20 · 30» (Android dazu 45 und 60, Apple höchstens 30); ein älterer Wert (15, 25) bleibt
            // als eigene Wahl erhalten.
            Picker(L("chart_window"), selection: $window) {
                ForEach(windowChoices(keeping: Int(window)), id: \.self) { minutes in
                    Text(minutes == AlarmSpec.exactWindow
                         ? L("editor_window_exact") : L("duration_minutes", Int64(minutes)))
                        .tag(Double(minutes))
                }
            }
            .pickerStyle(.segmented)
            // Grosse Segmente: Tippziel nahe 44 pt.
            .controlSize(.large)
            if Int(window) == AlarmSpec.exactWindow {
                Text(L("editor_window_exact_hint")).font(.footnote).foregroundStyle(.secondary)
            } else {
                Text(L("editor_window_hint", Format.time(time.addingTimeInterval(-window * 60)),
                       Format.time(time)))
                    .font(.footnote).foregroundStyle(.secondary)
            }
        } header: {
            Text(L("chart_window"))
        }
    }

    /// Wochentage einzeln und als Gruppe.
    @ViewBuilder
    private var daysSection: some View {
        Section(L("editor_days")) {
            // Ohne Abstand: Jeder Kreis hat seinen eigenen Rand, die Tippflächen grenzen lückenlos aneinander.
            HStack(spacing: 0) {
                ForEach(weekOrder, id: \.self) { day in
                    let on = days.contains(day)
                    DayToggle(label: dayName(day), on: on) { if on { days.remove(day) } else { days.insert(day) } }
                }
            }
            HStack {
                quickDays("editor_weekdays", [.monday, .tuesday, .wednesday, .thursday, .friday])
                quickDays("editor_weekend", [.saturday, .sunday])
                quickDays("alarm_every_day", Set(Weekday.allCases))
            }
            .buttonStyle(.borderless)
            .font(.footnote)
        }
    }

    /// Weckart am iPhone.
    @ViewBuilder
    private var alertSection: some View {
        Section {
            Text(L("editor_alert_hint")).font(.footnote).foregroundStyle(.secondary)
            // Auf Apple nur, was wirkt: Die Apple Watch weckt per Haptik (ohne Ton), das iPhone
            // klingelt über AlarmKit immer mit Ton.
            Picker(L("editor_alert_phone"), selection: $phone) {
                Text(L("alert_off")).tag(PhoneAlert.off)
                Text(L("alert_sound")).tag(PhoneAlert.sound)
            }
        } header: {
            Text(L("editor_alert"))
        }
    }

    /// Selten gebrauchte Einstellungen unter «Mehr».
    @ViewBuilder
    private var moreSection: some View {
        // Selten gebraucht, zugeklappt unter «Mehr» (wie Android): Sonnenaufgang, Feiertage, Weckton
        // und Name. Sichtbar bleiben Zeit, Fenster, Wiederholen und Weckart.
        Section {
            DisclosureGroup(isExpanded: $showMore) {
                // Sonnenaufgang. Nur auf dem iPhone: Die Apple Watch hält ihren Bildschirm in der
                // Smart-Alarm-Sitzung nicht zuverlässig an, darum keine Gerätewahl (gilt als «Handy»).
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("sunrise_title")).font(.headline)
                    // Auf dem iPhone leuchtet immer das iPhone: Hinweis zum Hinlegen gleich dazu.
                    Text(L("editor_sunrise_hint") + " " + L("editor_sunrise_phone_hint"))
                        .font(.footnote).foregroundStyle(.secondary)
                    Picker(L("sunrise_title"), selection: $sunrise) {
                        Text(L("alert_off")).tag(Sunrise.off)
                        Text(L("sunrise_ring")).tag(Sunrise.ring)
                        Text(L("sunrise_window")).tag(Sunrise.window)
                    }
                    .pickerStyle(.segmented)
                    .controlSize(.large)
                    if sunrise != .off {
                        Text(sunriseHint)
                            .font(.footnote).foregroundStyle(.secondary)
                        // Auf dem iPhone nur mit offener App möglich.
                        Text(L("editor_sunrise_ios_hint")).font(.footnote)
                    }
                }
                // Feiertage nur für wiederholte Wecker; Land und Region stehen in den Einstellungen.
                if !days.isEmpty { HolidaySkipRow(skipHolidays: $skipHolidays) }
                if phone != .off {
                    // Eigene Töne oder der Standardton des Systems; eine Auswahl spielt kurz an.
                    let choice = Binding(get: { sound }, set: { value in
                        sound = value
                        if let tone = WakeTone(rawValue: value) { preview.play(tone) } else {
                            preview.stop()
                        }
                    })
                    Picker(L("editor_sound"), selection: choice) {
                        ForEach(WakeTone.allCases, id: \.self) { tone in
                            Text(L(tone.textKey)).tag(tone.rawValue)
                        }
                        // Standardton: leer, oder ein vorhandener fremder Wert bleibt unverändert.
                        Text(L("editor_sound_default")).tag(WakeTone(rawValue: sound) == nil ? sound : "")
                    }
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("editor_label")).font(.headline)
                    TextField(L("editor_label_hint"), text: $label)
                }
            } label: {
                Text(L("editor_more"))
            }
        }
    }

    /// Löschen eines vorhandenen Weckers.
    @ViewBuilder
    private var deleteSection: some View {
        if let existing {
            Section {
                Button(L("editor_delete"), role: .destructive) { confirmDelete = true }
                    .confirmationDialog(L("editor_delete_title"), isPresented: $confirmDelete,
                                        titleVisibility: .visible) {
                        Button(L("editor_delete"), role: .destructive) {
                            deleting = true
                            model.remove(existing.id)
                            dismiss()
                        }
                    }
            }
        }
    }

    /// Hinweis zur gewählten Sonnenaufgangs-Art.
    private var sunriseHint: String {
        sunrise == .window
            ? L("editor_sunrise_window_hint")
            : LP("editor_sunrise_ring_hint", Sunrise.ringSeconds / 60, Sunrise.ringSeconds / 60)
    }

    private var dirty: Bool { original != nil && current() != original }

    /// Wahl des Fensters: «Genau» (0), 10, 20, 30 und ein gespeicherter Wert dazwischen, damit er nicht still ändert.
    private func windowChoices(keeping current: Int) -> [Int] {
        let base = [AlarmSpec.exactWindow, 10, 20, AlarmSpec.maxWindowMinutes]
        return base.contains(current) ? base : (base + [current]).sorted()
    }

    /// Knopf für eine Tagesgruppe (Werktage, Wochenende, täglich) mit Tippziel von mindestens 44 pt Höhe.
    private func quickDays(_ key: String, _ set: Set<Weekday>) -> some View {
        Button { days = set } label: {
            Text(L(key)).frame(minHeight: 44).contentShape(Rectangle())
        }
    }

    /// Wochentage ab dem Wochenbeginn der Region (z. B. Sonntag in den USA).
    private var weekOrder: [Weekday] {
        let firstRaw = (Calendar.current.firstWeekday + 5) % 7 + 1
        return (0..<7).compactMap { Weekday(rawValue: (firstRaw - 1 + $0) % 7 + 1) }
    }

    private func dayName(_ day: Weekday) -> String {
        var calendar = Calendar.current
        calendar.locale = .current
        return String(calendar.veryShortStandaloneWeekdaySymbols[day.rawValue % 7])
    }

    private func load() {
        defer { if original == nil { original = current() } }
        guard let existing else { return }
        time = Calendar.current.date(from: DateComponents(hour: existing.hour, minute: existing.minute)) ?? Date()
        window = Double(existing.windowMinutes)
        days = existing.days
        phone = existing.phone == .off ? .off : .sound
        watchSound = existing.watchSound
        label = existing.label
        sound = existing.sound
        sunrise = existing.sunrise
        skipHolidays = existing.skipHolidays
        sleepMode = existing.sleepDurationMinutes != nil
        sleepMinutes = Double(existing.sleepDurationMinutes ?? Self.defaultSleepMinutes)
        // Schon gesetzte Optionen nicht verstecken.
        showMore = !existing.label.isEmpty || WakeTone(rawValue: existing.sound) != nil || existing.sunrise != .off
            || existing.skipHolidays
    }

    private func current() -> AlarmSpec? { clamped().spec }

    /// Die Eingaben auf gültige Werte begrenzt statt verworfen (``ClampedSpec``, wie der Editor auf Android); die
    /// Diagnose-Zeile schreibt das Speichern.
    private func clamped() -> ClampedSpec {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        return ClampedSpec(
            id: existing?.id ?? newId,
            hour: c.hour ?? 7,
            minute: c.minute ?? 0,
            days: days,
            windowMinutes: Int(window),
            enabled: true,
            phone: phone,
            watchSound: watchSound,
            sleepDurationMinutes: sleepMode ? Int(sleepMinutes) : nil,
            sound: sound,
            label: String(label.trimmingCharacters(in: .whitespaces).prefix(40)),
            sunrise: sunrise,
            // Weckaufgabe gibt es auf Apple nicht (AlarmKit-Stopp lässt sich nicht ersetzen): nicht
            // anbieten, einen vorhandenen Wert aber unverändert behalten.
            wakeTask: existing?.wakeTask ?? WakeTask.none,
            // Gerätewahl gibt es auf Apple nicht (nur iPhone): vorhandenen Wert behalten, neu = Handy.
            sunriseDevice: existing?.sunriseDevice ?? .phone,
            // «Nächstes Mal überspringen» bleibt nur bei gleicher Zeit und gleichen Tagen (`save`, `keepSkip`).
            skipUntil: existing?.skipUntil ?? 0,
            skipHolidays: skipHolidays
        )
    }

    /// Entscheid fürs Speichern aus den gespeicherten Weckern `ids`.
    private func check(_ ids: [String]) -> EditorSave {
        EditorSave.check(existed: existing != nil, storedIds: ids, draftId: existing?.id ?? newId,
                         maxAlarms: PhoneModel.maxAlarms)
    }

    private func save() {
        switch check(model.alarms.map(\.id)) {
        case .gone: gone = true; return
        case .full: full = true; return
        case .save: break
        }
        // Geänderte Zeit oder Tage heben das Überspringen auf (wie Android und die Uhren).
        let checked = clamped()
        if let line = checked.diagLine { DiagStore.shared.log(.error, line) }
        let spec = checked.spec
        model.upsert(existing.map { spec.with(skipUntil: AlarmSchedule.keepSkip(old: $0, edited: spec)) } ?? spec)
        dismiss()
    }
}

/// «An Feiertagen nicht wecken» mit Hinweis auf die Einstellungen (Zeile unter «Mehr»).
private struct HolidaySkipRow: View {
    @Binding var skipHolidays: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle(L("editor_skip_holidays"), isOn: $skipHolidays)
            Text(L("editor_skip_holidays_hint")).font(.footnote).foregroundStyle(.secondary)
        }
    }
}
