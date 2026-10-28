import Foundation

/// Pluralformen wie Android (`<plurals>`): Kategorie nach den CLDR-Regeln für ganze Zahlen, nur für
/// die Sprachen der App. Die Texte stehen im Katalog als «name|kategorie».
enum Plural {
    static func category(_ n: Int, language: String) -> String {
        let n = abs(n)
        let mod10 = n % 10, mod100 = n % 100
        switch language {
        case "ar":
            if n == 0 { return "zero" }
            if n == 1 { return "one" }
            if n == 2 { return "two" }
            if (3...10).contains(mod100) { return "few" }
            if (11...99).contains(mod100) { return "many" }
            return "other"
        case "cs":
            return n == 1 ? "one" : (2...4).contains(n) ? "few" : "other"
        case "pl":
            if n == 1 { return "one" }
            return (2...4).contains(mod10) && !(12...14).contains(mod100) ? "few" : "many"
        case "ru", "uk":
            if mod10 == 1 && mod100 != 11 { return "one" }
            return (2...4).contains(mod10) && !(12...14).contains(mod100) ? "few" : "many"
        case "ro":
            if n == 1 { return "one" }
            return n == 0 || (2...19).contains(mod100) ? "few" : "other"
        case "he":
            return n == 1 ? "one" : n == 2 ? "two" : "other"
        case "fr", "hi", "fa":
            return n <= 1 ? "one" : "other"
        case "pt":
            let region = Locale.current.region?.identifier
            return (region == "PT" ? n == 1 : n <= 1) ? "one" : "other"
        case "ja", "ko", "zh", "th", "vi", "id", "ms":
            return "other"
        default:
            return n == 1 ? "one" : "other"
        }
    }
}

/// Text mit Pluralform: `count` wählt die Form, `args` füllen die Platzhalter.
func LP(_ key: String, _ count: Int, _ args: CVarArg...) -> String {
    let language = Bundle.main.preferredLocalizations.first.map { Locale(identifier: $0).language.languageCode?.identifier ?? "en" } ?? "en"
    let category = Plural.category(count, language: language)
    let missing = "\u{0}"
    var format = Bundle.main.localizedString(forKey: "\(key)|\(category)", value: missing, table: nil)
    if format == missing { format = Bundle.main.localizedString(forKey: "\(key)|other", value: nil, table: nil) }
    return String(format: format, locale: .current, arguments: args)
}
