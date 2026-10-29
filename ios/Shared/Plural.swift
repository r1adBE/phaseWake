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
        case "fr", "hi", "fa", "bn", "hy", "si", "gu", "kn", "pa", "am", "zu":
            return n <= 1 ? "one" : "other"
        case "hr", "sr", "bs":
            if mod10 == 1 && mod100 != 11 { return "one" }
            return (2...4).contains(mod10) && !(12...14).contains(mod100) ? "few" : "other"
        case "sl":
            return mod100 == 1 ? "one" : mod100 == 2 ? "two" : (3...4).contains(mod100) ? "few" : "other"
        case "sk":
            return n == 1 ? "one" : (2...4).contains(n) ? "few" : "other"
        case "lt":
            if (11...19).contains(mod100) { return "other" }
            return mod10 == 1 ? "one" : mod10 >= 2 ? "few" : "other"
        case "lv":
            if mod10 == 0 || (11...19).contains(mod100) { return "zero" }
            return mod10 == 1 ? "one" : "other"
        case "mk", "is":
            return mod10 == 1 && mod100 != 11 ? "one" : "other"
        case "ga":
            return n == 1 ? "one" : n == 2 ? "two" : (3...6).contains(n) ? "few" : (7...10).contains(n) ? "many" : "other"
        case "km", "lo", "my":
            return "other"
        case "fil":
            return [4, 6, 9].contains(mod10) ? "other" : "one"
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
