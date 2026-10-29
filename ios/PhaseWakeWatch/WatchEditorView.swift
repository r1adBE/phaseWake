import PhaseWakeCore
import SwiftUI

extension AlarmSpec {
    /// Neuer Wecker mit denselben Vorgaben wie im iPhone-Editor: jetzige Uhrzeit, Werktage, Fenster 30 Min.
    static func newOnWatch(now: Date = Date()) -> AlarmSpec {
        let c = Calendar.current.dateComponents([.hour, .minute], from: now)
        // Werte im gültigen Bereich; die Prüfung kann nicht scheitern.
        return try! AlarmSpec(
            id: UUID().uuidString, hour: c.hour ?? 7, minute: c.minute ?? 0,
            days: [.monday, .tuesday, .wednesday, .thursday, .friday]
        )
    }

    /// Kopie mit Zeit, Fenster, Tagen, Ein/Aus und «Nächstes Mal überspringen» aus `draft`; alles andere
    /// (Sonnenaufgang, Töne, iPhone-Wecker, Aufgabe, Schlafdauer, Name, Feiertage) bleibt, wie es am iPhone eingestellt ist.
    func editedOnWatch(from draft: AlarmSpec) -> AlarmSpec {
        // Werte stammen aus gültigen Weckern; die Prüfung kann nicht scheitern.
        try! AlarmSpec(
            id: id, hour: draft.hour, minute: draft.minute, days: draft.days, windowMinutes: draft.windowMinutes,
            enabled: draft.enabled, phone: phone, watchSound: watchSound, sleepDurationMinutes: sleepDurationMinutes,
            sound: sound, label: label, sunrise: sunrise, wakeTask: wakeTask, sunriseDevice: sunriseDevice,
            skipUntil: draft.skipUntil, skipHolidays: skipHolidays
        )
    }
}

/// Einfacher Wecker-Editor auf der Uhr: Uhrzeit, Weckfenster, Wochentage, Ein/Aus, Löschen.
/// Abbrechen (oben links) verwirft die Änderungen.
struct WatchEditorView: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    /// Der Wecker beim Öffnen; bei einem neuen die Vorgaben aus `AlarmSpec.newOnWatch`.
    let start: AlarmSpec
    let isNew: Bool

    @State private var hour = 7
    @State private var minute = 0
    @State private var window = AlarmSpec.defaultWindowMinutes
    @State private var days: Set<Weekday> = []
    @State private var enabled = true
    /// «Nächstes Mal überspringen» (nur wiederholte Wecker).
    @State private var skipNext = false
    @State private var loaded = false
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            List {
                // Bei Schlafdauer ist die Uhrzeit die späteste Weckzeit. Die Krone dreht das gewählte Rad.
                Section(L(start.sleepDurationMinutes != nil ? "editor_latest" : "editor_target")) {
                    HStack(spacing: 2) {
                        Picker(L("editor_target"), selection: $hour) {
                            ForEach(0..<24, id: \.self) { Text(String(format: "%02d", $0)).tag($0) }
                        }
                        .accessibilityValue(Format.time(hour: hour, minute: minute))
                        Text(":").font(Fonts.manrope(20, .bold, relativeTo: .title3)).accessibilityHidden(true)
                        Picker(L("editor_target"), selection: $minute) {
                            ForEach(0..<60, id: \.self) { Text(String(format: "%02d", $0)).tag($0) }
                        }
                        .accessibilityValue(Format.time(hour: hour, minute: minute))
                    }
                    .pickerStyle(.wheel)
                    .labelsHidden()
                    .frame(height: 76)
                    .listRowBackground(Color.clear)
                }
                Section {
                    Stepper(value: $window, in: AlarmSpec.minWindowMinutes...AlarmSpec.maxWindowMinutes, step: 5) {
                        Text(L("editor_window", Int64(window)))
                            .font(Fonts.manrope(14, .semibold, relativeTo: .body))
                    }
                }
                Section(L("editor_days")) {
                    // Ohne Tag gilt der Wecker einmal.
                    Text(DayFormat.format(days, locale: .current, once: L("alarm_once"), daily: L("alarm_every_day")))
                        .font(Fonts.manrope(12, relativeTo: .footnote))
                        .foregroundStyle(Brand.muted)
                        .listRowBackground(Color.clear)
                    ForEach(weekOrder, id: \.self) { day in
                        Toggle(dayName(day), isOn: Binding(
                            get: { days.contains(day) },
                            set: { on in if on { days.insert(day) } else { days.remove(day) } }
                        ))
                        .tint(Brand.echo)
                    }
                }
                Section {
                    Toggle(L("editor_enabled"), isOn: $enabled).tint(Brand.echo)
                    if !days.isEmpty {
                        Toggle(L("alarm_skip"), isOn: $skipNext).tint(Brand.echo)
                        if skipNext, let date = skipDate {
                            Text(L("alarm_skipped", Format.shortDay(date)))
                                .font(Fonts.manrope(12, relativeTo: .footnote))
                                .foregroundStyle(Brand.muted)
                        }
                    }
                }
                if !isNew {
                    Section {
                        Button(L("editor_delete"), role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle(L(isNew ? "editor_new" : "editor_edit"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("action_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("editor_save")) { save() }
                }
            }
            .confirmationDialog(L("editor_delete_title"), isPresented: $confirmDelete, titleVisibility: .visible) {
                Button(L("editor_delete"), role: .destructive) {
                    model.remove(start.id)
                    dismiss()
                }
                Button(L("action_cancel"), role: .cancel) {}
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        hour = start.hour
        minute = start.minute
        window = start.windowMinutes
        days = start.days
        enabled = start.enabled
        skipNext = AlarmSchedule.skippedDate(start, now: Date(), calendar: .current) != nil
    }

    /// Stand des Editors; Überspringen wie beim Öffnen (das Modell wendet `skipEdit` an).
    private var draft: AlarmSpec? {
        try? AlarmSpec(
            id: start.id, hour: hour, minute: minute, days: days, windowMinutes: window, enabled: enabled,
            phone: start.phone, watchSound: start.watchSound, sleepDurationMinutes: start.sleepDurationMinutes,
            sound: start.sound, label: start.label, sunrise: start.sunrise, wakeTask: start.wakeTask,
            sunriseDevice: start.sunriseDevice, skipUntil: start.skipUntil, skipHolidays: start.skipHolidays
        )
    }

    /// Ob der Schalter «Nächstes Mal überspringen» angefasst wurde (wie beim Öffnen → `keep`).
    private var skipEdit: WatchModel.SkipEdit {
        let wasSkipped = AlarmSchedule.skippedDate(start, now: Date(), calendar: .current) != nil
        if skipNext == wasSkipped { return .keep }
        return skipNext ? .on : .off
    }

    /// Datum unter dem Schalter: der schon übersprungene Termin, oder der, den Einschalten überspringen würde.
    private var skipDate: String? {
        guard skipEdit == .on, let draft else { return AlarmSchedule.skippedDate(start, now: Date(), calendar: .current) }
        return AlarmSchedule.nextSkipDate(draft, now: Date(), doneUntil: model.storage.doneUntil[start.id], calendar: .current,
                                          holidays: model.storage.holidays.dates)
    }

    private func save() {
        if let draft { model.save(draft, skip: draft.isOneShot ? .off : skipEdit) }
        dismiss()
    }

    /// Wochentage ab dem Wochenbeginn der Region (z. B. Sonntag in den USA).
    private var weekOrder: [Weekday] {
        let firstRaw = (Calendar.current.firstWeekday + 5) % 7 + 1
        return (0..<7).compactMap { Weekday(rawValue: (firstRaw - 1 + $0) % 7 + 1) }
    }

    private func dayName(_ day: Weekday) -> String {
        var calendar = Calendar.current
        calendar.locale = .current
        return calendar.standaloneWeekdaySymbols[day.rawValue % 7].capitalized(with: .current)
    }
}
