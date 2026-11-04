import SwiftUI

/// Lizenzen der iPhone-App: oben die Übersicht (übersetzt), darunter je Bestandteil seine Lizenz mit dem vollen Text.
/// Die Texte liegen im Ordner `licenses/` des Projekts (wie bei Android) und kommen beim Build ins Bundle; Namen und
/// Lizenztexte bleiben unübersetzt. Die Uhr-App enthält keinen fremden Code; die Schrift Manrope trägt dort Copyright
/// und Lizenz in ihren Metadaten.
struct LicensesView: View {
    /// Ein Bestandteil mit seiner Lizenz; `file` = Textdatei im Bundle ohne Endung «.txt».
    struct Component: Identifiable {
        let name: String
        let license: String
        let file: String
        var id: String { file }
    }

    /// Was die iPhone-App enthält. Die Geräuscherkennung (SoundAnalysis) gehört zu iOS und bringt keinen eigenen Text mit.
    static let components = [
        Component(name: "Phase Wake", license: "MIT License", file: "MIT-phasewake"),
        Component(name: "Manrope", license: "SIL Open Font License 1.1", file: "Manrope-OFL"),
        Component(name: "holidays (Python)", license: "MIT License", file: "MIT-holidays"),
        Component(name: "Sounds (opengameart.org)", license: "CC0 1.0 Universal", file: "CC0-sounds"),
    ]

    var body: some View {
        List {
            Section {
                Text(L("settings_licenses_text_apple") + "\n" + L("settings_license_holidays"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section {
                ForEach(Self.components) { component in
                    // Fehlt eine Datei im Bundle, fehlt nur ihre Zeile (die Übersicht oben bleibt).
                    if let text = Self.text(component.file) {
                        NavigationLink {
                            LicenseTextView(title: component.name, text: text)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: component.name)
                                Text(verbatim: component.license).font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(L("settings_licenses"))
    }

    /// Voller Text der Lizenzdatei `file` aus dem Bundle, oder `nil`, wenn sie fehlt.
    static func text(_ file: String) -> String? {
        guard let url = Bundle.main.url(forResource: file, withExtension: "txt") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }
}

/// Voller Lizenztext eines Bestandteils, auswählbar zum Kopieren.
private struct LicenseTextView: View {
    let title: String
    let text: String

    var body: some View {
        ScrollView {
            Text(verbatim: text)
                .font(.footnote)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
