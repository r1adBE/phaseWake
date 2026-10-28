import PhaseWakeCore
import SwiftUI

/// Einstellungen: Darstellung, Gesundheitsdaten, Daten, Über.
struct SettingsView: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("themeMode") private var themeMode = ThemeMode.system
    @State private var confirmClear = false

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
                    Button(L("health_connect")) { Task { _ = await model.health.requestAccess() } }
                }
                Section(L("settings_data")) {
                    Text(L("settings_data_text")).font(.footnote)
                    ShareLink(item: NightHistory.exportCsv(model.nights), preview: SharePreview(L("history_export_subject"))) {
                        Text(L("settings_export"))
                    }
                    Button(L("settings_clear_history"), role: .destructive) { confirmClear = true }
                }
                Section(L("settings_about")) {
                    Link(L("settings_open_source"), destination: URL(string: "https://github.com/r1adBE/phaseWake")!)
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
