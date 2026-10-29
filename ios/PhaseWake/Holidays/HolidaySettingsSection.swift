import PhaseWakeCore
import SwiftUI

/// Einstellungen «Feiertage»: Land (Vorgabe: Region des Geräts) und Region (oder ganzes Land). Ohne Standort,
/// ohne Internet; lokale Feiertage einzelner Gemeinden fehlen in den Daten.
struct HolidaySettingsSection: View {
    @Environment(PhoneModel.self) private var model

    var body: some View {
        let store = model.holidays
        Section(L("settings_holidays")) {
            Picker(L("holidays_country"), selection: Binding(get: { store.country }, set: { select($0, "") })) {
                Text(L("holidays_off")).tag("")
                ForEach(store.countries, id: \.code) { country in
                    Text(country.name).tag(country.code)
                }
                // Gespeichertes Land, das (noch) nicht geladen ist, bleibt wählbar.
                if !store.country.isEmpty, !store.countries.contains(where: { $0.code == store.country }) {
                    Text(Locale.current.localizedString(forRegionCode: store.country) ?? store.country).tag(store.country)
                }
            }
            if !store.regions.isEmpty {
                Picker(L("holidays_region"), selection: Binding(get: { store.region }, set: { select(store.country, $0) })) {
                    Text(L("holidays_whole_country")).tag("")
                    ForEach(store.regions, id: \.code) { region in
                        Text(region.name).tag(region.code)
                    }
                }
            }
            if let next = store.dates.min() {
                Text(L("holidays_next", Format.shortDay(next))).font(.footnote)
            }
            Text(L("holidays_hint")).font(.footnote).foregroundStyle(.secondary)
        }
    }

    private func select(_ country: String, _ region: String) {
        model.holidays.select(country: country, region: region)
        model.holidaysChanged()
    }
}
