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
                        Text(L("editor_advice_text", advice.deadlineNights, advice.nights, advice.windowMinutes)).font(.footnote)
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
                        ForEach(Weekday.allCases, id: \.self) { day in
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
                    Picker(L("editor_alert_watch"), selection: $watchSound) {
                        Text(L("alert_vibrate_only")).tag(false)
                        Text(L("alert_vibrate_sound")).tag(true)
                    }
                    Picker(L("editor_alert_phone"), selection: $phone) {
                        Text(L("alert_off")).tag(PhoneAlert.off)
                        Text(L("alert_vibrate")).tag(PhoneAlert.vibrate)
                        Text(L("alert_sound")).tag(PhoneAlert.sound)
                    }
                } header: {
                    Text(L("editor_alert"))
                }

                Section {
                    TextField(L("editor_label_hint"), text: $label)
                } header: {
                    Text(L("editor_label"))
                }

                if let existing {
                    Section {
                        Button(L("editor_delete"), role: .destructive) {
                            model.remove(existing.id)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(L(existing == nil ? "editor_new" : "editor_edit"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(L("editor_save")) { save() }.bold() }
            }
            .onAppear(perform: load)
        }
    }

    private func dayName(_ day: Weekday) -> String {
        var calendar = Calendar.current
        calendar.locale = .current
        return String(calendar.veryShortStandaloneWeekdaySymbols[day.rawValue % 7])
    }

    private func load() {
        guard let existing else { return }
        time = Calendar.current.date(from: DateComponents(hour: existing.hour, minute: existing.minute)) ?? Date()
        window = Double(existing.windowMinutes)
        days = existing.days
        sleepMode = existing.sleepDurationMinutes != nil
        sleepMinutes = Double(existing.sleepDurationMinutes ?? 450)
        phone = existing.phone
        watchSound = existing.watchSound
        label = existing.label
    }

    private func save() {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        if let spec = try? AlarmSpec(
            id: existing?.id ?? UUID().uuidString,
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
            sunrise: existing?.sunrise ?? .off
        ) {
            model.upsert(spec)
        }
        dismiss()
    }
}
