import Foundation
import XCTest
@testable import PhaseWakeWatch

/// Der gekürzte Text-Katalog der Watch-App ist vollständig (Audit F9): Jeder Schlüssel, den `gen-xcstrings.py` für die
/// Uhr ermittelt hat (ios/PhaseWakeWatch/Localizable.xcstrings), steht in jeder Sprache des Katalogs in der gebauten
/// App und liefert dort einen Text statt des Schlüssels. Zusammen mit der statischen Prüfung des Skripts (jedes
/// `L("…")`/`LP("…")` der Uhr steht im Katalog) zeigt die Uhr nie einen Schlüssel.
///
/// Gelesen werden die übersetzten Tabellen der App (`<sprache>.lproj/Localizable.strings` in `Bundle.main`, dem
/// Testwirt), dieselben, aus denen `L` und `LP` lesen. Ein eigener Aufruf von `localizedString` je Sprache ginge nur
/// über ein zweites Bundle; das Skript lässt ihn ausserhalb der Helfer nicht zu (und `L` liest nur die Sprache des
/// Testwirts). Pluralformen: `LP` fällt auf «name|other» zurück, darum darf nur diese Form in keiner Sprache fehlen.
final class CatalogKeysWatchTests: XCTestCase {
    /// Quelle des Katalogs im Repository; der Simulator liest die Dateien des Macs.
    private static let catalogURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("PhaseWakeWatch").appendingPathComponent("Localizable.xcstrings")

    /// Schlüssel → Sprachen, in denen der Quell-Katalog ihn übersetzt; `nil`, wenn er nicht lesbar ist.
    private func sourceCatalog() -> [String: Set<String>]? {
        guard let data = try? Data(contentsOf: Self.catalogURL),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let strings = root["strings"] as? [String: Any] else { return nil }
        var out: [String: Set<String>] = [:]
        for (key, entry) in strings {
            let units = (entry as? [String: Any])?["localizations"] as? [String: Any] ?? [:]
            out[key] = Set(units.keys)
        }
        return out
    }

    /// Gleiche Schreibweise für Sprachkennungen aus Katalog und Bundle («pt-BR», «pt_BR», «zh-hans»).
    private static func normalized(_ language: String) -> String {
        language.replacingOccurrences(of: "_", with: "-").lowercased()
    }

    /// Übersetzte Tabelle der App für `language`; `nil`, wenn die Sprache oder die Tabelle fehlt.
    private func table(_ language: String) -> [String: String]? {
        let wanted = Self.normalized(language)
        if let folder = Bundle.main.localizations.first(where: { Self.normalized($0) == wanted }),
           let dir = Bundle.main.url(forResource: folder, withExtension: "lproj"),
           let strings = NSDictionary(contentsOf: dir.appendingPathComponent("Localizable.strings"))
            as? [String: String] {
            return strings
        }
        // Xcode kann alle Sprachen auch in einer Tabelle ablegen («Localizable.loctable», Sprache → Texte).
        guard let url = Bundle.main.url(forResource: "Localizable", withExtension: "loctable"),
              let all = NSDictionary(contentsOf: url) as? [String: Any],
              let texts = all.first(where: { Self.normalized($0.key) == wanted })?.value as? [String: Any]
        else { return nil }
        return texts.compactMapValues { $0 as? String }
    }

    /// Ersatz, wenn der Quell-Katalog nicht lesbar ist: Schlüssel der englischen Tabelle, erwartet in allen Sprachen
    /// der App (Pluralformen ausser «other» ohne Erwartung, wie im Katalog).
    private func fallbackCatalog() -> [String: Set<String>] {
        let languages = Set(Bundle.main.localizations.filter { $0 != "Base" })
        var out: [String: Set<String>] = [:]
        for key in (table("en") ?? [:]).keys {
            out[key] = key.contains("|") && !key.hasSuffix("|other") ? [] : languages
        }
        return out
    }

    /// Befunde für eine Sprache: Schlüssel, die fehlen, leer sind oder den Schlüssel selbst zeigen.
    static func problems(in table: [String: String], language: String, catalog: [String: Set<String>]) -> [String] {
        var out: [String] = []
        for (key, translated) in catalog.sorted(by: { $0.key < $1.key }) {
            let name = key.split(separator: "|", maxSplits: 1).first.map(String.init) ?? key
            let plural = name != key
            guard translated.contains(language) else {
                // Andere Pluralformen braucht nicht jede Sprache; ohne «other» zeigte `LP` den Schlüssel.
                if !plural || key.hasSuffix("|other") { out.append("\(language): \(key) fehlt im Katalog") }
                continue
            }
            guard let value = table[key] else {
                out.append("\(language): \(key) fehlt in der App")
                continue
            }
            if value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || value == key || value == name {
                out.append("\(language): \(key) ohne Text")
            }
        }
        return out
    }

    func testEveryCatalogKeyHasTextInEveryLanguage() {
        XCTAssertEqual(Bundle.main.bundleIdentifier, "com.phasewake.app.watchkitapp", "Testwirt ist nicht die Uhr-App")
        let source = sourceCatalog()
        let catalog = source ?? fallbackCatalog()
        let note = XCTAttachment(string: source != nil ? "Quelle: \(Self.catalogURL.path)"
                                 : "Quelle: en.lproj der App (Katalog im Repository nicht lesbar)")
        note.lifetime = .keepAlways
        add(note)
        XCTAssertGreaterThan(catalog.count, 0, "Katalog leer")
        let languages = Set(catalog.values.flatMap { $0 })
        XCTAssertGreaterThan(languages.count, 1, "nur eine Sprache")
        var problems: [String] = []
        for language in languages.sorted() {
            guard let table = table(language) else {
                problems.append("\(language): keine Tabelle in der App")
                continue
            }
            problems += Self.problems(in: table, language: language, catalog: catalog)
        }
        XCTAssertTrue(problems.isEmpty, "\(problems.count) Befunde:\n" + problems.prefix(40).joined(separator: "\n"))
    }

    /// Die Helfer selbst lesen in der Sprache des Testwirts einen Text (einfach und Plural).
    func testHelpersResolveInRunningLanguage() {
        XCTAssertNotEqual(L("action_cancel"), "action_cancel")
        XCTAssertFalse(LP("nap_minutes", 20, Int64(20)).contains("nap_minutes"))
    }

    /// Selbsttest der Auswertung: fehlender, leerer und als Schlüssel gezeigter Text sowie Pluralformen.
    func testProblemsFindsMissingAndKeyLikeTexts() {
        let catalog: [String: Set<String>] = ["a": ["de"], "b": ["de"], "c": ["de"], "p|one": [], "p|other": ["de"],
                                              "q|other": []]
        let found = Self.problems(in: ["a": "Text", "b": "b", "p|other": " "], language: "de", catalog: catalog)
        XCTAssertEqual(found, ["de: b ohne Text", "de: c fehlt in der App", "de: p|other ohne Text",
                               "de: q|other fehlt im Katalog"])
    }
}
