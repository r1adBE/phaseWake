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
    @State private var sleepMode = false
    @State private var sleepMinutes = 450.0
    @State private var phone = PhoneAlert.sound
    @State private var watchSound = false
    @State private var label = ""
    @State private var sunrise = Sunrise.off
    @State private var original: AlarmSpec?
    @State private var confirmDiscard = false
    @State private var confirmDelete = false
    @State private var newId = UUID().uuidString
    @State private var showMore = false

    var body: some View {
        NavigationStack {
            Form {
                // Einschlaf-Modus gibt es auf Apple nicht: Die Smart-Alarm-Sitzung der Apple Watch
                // läuft höchstens 30 Minuten und kann die Nacht nicht ab «Gute Nacht» begleiten.
                Section(L(sleepMode ? "editor_latest" : "editor_target")) {
                    DatePicker("", selection: $time, displayedComponents: .hourAndMinute)
                        .datePickerStyle(.wheel)
                        .labelsHidden()
                        .frame(maxWidth: .infinity)
                }

                if sleepMode {
                    Section {
                        Text(L("editor_sleep_duration", Format.duration(minutes: Int(sleepMinutes))))
                        Slider(value: $sleepMinutes, in: 240...600, step: 15)
                        Text(L("editor_sleep_hint")).font(.footnote).foregroundStyle(.secondary)
                    }
                }

                if let existing, let advice = WindowAdvice.suggest(existing, history: model.nights), Int(window) < advice.windowMinutes {
                    Section {
                        Text(L("editor_advice_title")).font(.headline)
                        Text(LP("editor_advice_text", advice.nights, advice.deadlineNights, advice.nights, advice.windowMinutes)).font(.footnote)
                        Button(L("editor_advice_apply", advice.windowMinutes)) { window = Double(advice.windowMinutes) }
                    }
                }

                Section {
                    Text(L("editor_window", Int(window)))
                    Slider(value: $window, in: Double(AlarmSpec.minWindowMinutes)...Double(AlarmSpec.maxWindowMinutes), step: 5)
                    if !sleepMode {
                        Text(L("editor_window_hint", Format.time(time.addingTimeInterval(-window * 60)), Format.time(time)))
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }

                Section(L("editor_days")) {
                    HStack {
                        ForEach(weekOrder, id: \.self) { day in
                            let on = days.contains(day)
                            Button(dayName(day)) { if on { days.remove(day) } else { days.insert(day) } }
                                .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                                .frame(maxWidth: .infinity, minHeight: 36)
                                .background(on ? Brand.accentLight : Color.secondary.opacity(0.12), in: Capsule())
                                .foregroundStyle(on ? .white : .primary)
                                .buttonStyle(.plain)
                                .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                    HStack {
                        Button(L("editor_weekdays")) { days = [.monday, .tuesday, .wednesday, .thursday, .friday] }
                        Button(L("editor_weekend")) { days = [.saturday, .sunday] }
                        Button(L("alarm_every_day")) { days = Set(Weekday.allCases) }
                    }
                    .buttonStyle(.borderless)
                    .font(.footnote)
                }

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

                // Sonnenaufgang im Haupteditor. Nur auf dem iPhone: Die Apple Watch hält ihren Bildschirm in
                // der Smart-Alarm-Sitzung nicht zuverlässig an, darum keine Gerätewahl (gilt als «Handy»).
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("editor_sunrise")).font(.headline)
                        Text(L("editor_sunrise_hint")).font(.footnote).foregroundStyle(.secondary)
                        Picker(L("editor_sunrise"), selection: $sunrise) {
                            Text(L("alert_off")).tag(Sunrise.off)
                            Text(L("sunrise_ring")).tag(Sunrise.ring)
                            Text(L("sunrise_window")).tag(Sunrise.window)
                        }
                        .pickerStyle(.segmented)
                        if sunrise != .off {
                            Text(sunrise == .window ? L("editor_sunrise_window_hint") : LP("editor_sunrise_ring_hint", Sunrise.ringSeconds / 60, Sunrise.ringSeconds / 60))
                                .font(.footnote).foregroundStyle(.secondary)
                            // Auf dem iPhone nur mit offener App möglich.
                            Text(L("editor_sunrise_ios_hint")).font(.footnote)
                        }
                    }
                }

                // Selten gebraucht: Name zugeklappt unter «Mehr».
                Section {
                    DisclosureGroup(isExpanded: $showMore) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(L("editor_label")).font(.headline)
                            TextField(L("editor_label_hint"), text: $label)
                        }
                    } label: {
                        Text(L("editor_more"))
                    }
                }

                if let existing {
                    Section {
                        Button(L("editor_delete"), role: .destructive) { confirmDelete = true }
                            .confirmationDialog(L("editor_delete_title"), isPresented: $confirmDelete, titleVisibility: .visible) {
                                Button(L("editor_delete"), role: .destructive) {
                                    model.remove(existing.id)
                                    dismiss()
                                }
                            }
                    }
                }
            }
            .navigationTitle(L(existing == nil ? "editor_new" : "editor_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // Bei Änderungen zuerst fragen; Wischen zum Schliessen ist dann gesperrt.
                    Button(L("action_cancel")) { if dirty { confirmDiscard = true } else { dismiss() } }
                        .confirmationDialog(L("editor_discard_title"), isPresented: $confirmDiscard, titleVisibility: .visible) {
                            Button(L("editor_discard"), role: .destructive) { dismiss() }
                            Button(L("editor_keep_editing"), role: .cancel) {}
                        }
                }
                ToolbarItem(placement: .confirmationAction) { Button(L("editor_save")) { save() }.bold() }
            }
            .onAppear(perform: load)
            .interactiveDismissDisabled(dirty)
        }
    }

    private var dirty: Bool { original != nil && current() != original }

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
        sleepMode = existing.sleepDurationMinutes != nil
        sleepMinutes = Double(existing.sleepDurationMinutes ?? 450)
        phone = existing.phone == .off ? .off : .sound
        watchSound = existing.watchSound
        label = existing.label
        sunrise = existing.sunrise
        // Schon gesetzte Optionen nicht verstecken.
        showMore = !existing.label.isEmpty
    }

    private func current() -> AlarmSpec? {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        return try? AlarmSpec(
            id: existing?.id ?? newId,
            hour: c.hour ?? 7,
            minute: c.minute ?? 0,
            days: days,
            windowMinutes: Int(window),
            enabled: true,
            phone: phone,
            watchSound: watchSound,
            sleepDurationMinutes: sleepMode ? Int(sleepMinutes) : nil,
            sound: existing?.sound ?? "",
            label: String(label.trimmingCharacters(in: .whitespaces).prefix(40)),
            sunrise: sunrise,
            // Weckaufgabe gibt es auf Apple nicht (AlarmKit-Stopp lässt sich nicht ersetzen): nicht
            // anbieten, einen vorhandenen Wert aber unverändert behalten.
            wakeTask: existing?.wakeTask ?? WakeTask.none,
            // Gerätewahl gibt es auf Apple nicht (nur iPhone): vorhandenen Wert behalten, neu = Handy.
            sunriseDevice: existing?.sunriseDevice ?? .phone
        )
    }

    private func save() {
        if let spec = current() { model.upsert(spec) }
        dismiss()
    }
}
