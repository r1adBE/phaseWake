import PhaseWakeCore
import SwiftUI

/// Einstellungen: Darstellung, Gesundheitsdaten, Daten, Über.
struct SettingsView: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("themeMode") private var themeMode = ThemeMode.system
    @State private var confirmClear = false
    @AppStorage("healthRequested") private var healthRequested = false

    var body: some View {
        NavigationStack {
            Form {
                Section(L("settings_theme")) {
                    Picker(L("settings_theme"), selection: $themeMode) {
                        Text(L("settings_theme_system")).tag(ThemeMode.system)
                        Text(L("settings_theme_light")).tag(ThemeMode.light)
                        Text(L("settings_theme_dark")).tag(ThemeMode.dark)
                    }
                    .pickerStyle(.segmented)
                }
                Section(L("health_connect")) {
                    Text(L("health_rationale")).font(.footnote)
                    Button(L("health_connect")) { Task { healthRequested = await model.health.requestAccess() } }
                }
                Section(L("goal_title")) {
                    Stepper(value: Binding(get: { model.sleepGoal }, set: { model.setSleepGoal($0) }),
                            in: SleepGoal.minMinutes...SleepGoal.maxMinutes, step: SleepGoal.stepMinutes) {
                        Text(L("goal_value", Format.duration(minutes: model.sleepGoal)))
                    }
                    Text(L("goal_hint")).font(.footnote).foregroundStyle(.secondary)
                }
                Section {
                    Toggle(L("bedtime_title"), isOn: Binding(get: { model.bedtimeReminder }, set: { model.setBedtimeReminder($0) }))
                    Text(L("bedtime_text", Bedtime.leadMinutes)).font(.footnote).foregroundStyle(.secondary)
                }
                CustomNotesSection()
                Section(L("settings_data")) {
                    Text(L("settings_data_text")).font(.footnote)
                    ShareLink(item: exportFile(model.nights, notes: model.notes), preview: SharePreview(L("history_export_subject"))) {
                        Text(L("settings_export"))
                    }
                    .disabled(model.nights.isEmpty)
                    Text(L("export_contents_hint")).font(.footnote).foregroundStyle(.secondary)
                    Button(L("settings_clear_history"), role: .destructive) { confirmClear = true }
                }
                Section(L("settings_about")) {
                    Link(L("settings_open_source"), destination: URL(string: "https://github.com/r1adBE/phaseWake")!)
                    Link(L("settings_privacy"), destination: URL(string: "https://github.com/r1adBE/phaseWake/blob/main/docs/DATENSCHUTZ.md")!)
                    VStack(alignment: .leading, spacing: 4) {
                        Link(L("settings_support"), destination: URL(string: "https://github.com/sponsors/r1adBE")!)
                        Text(L("settings_support_text")).font(.footnote).foregroundStyle(.secondary)
                    }
                    Text(L("settings_licenses_text")).font(.footnote).foregroundStyle(.secondary)
                    Text(L("settings_version", Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""))
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(L("settings_title"))
            .confirmationDialog(L("settings_clear_history_text"), isPresented: $confirmClear, titleVisibility: .visible) {
                Button(L("settings_clear_history_confirm"), role: .destructive) { model.clearHistory() }
            }
        }
    }
}
