import Foundation

/// Anzeige des Validierungs-Modus (``Validation``): Abschnitt im Diagnose-Bericht (``lines(_:)``) und CSV-Export
/// (``exportCsv(_:)``, Datei «phasewake-validation.csv»). Technische Zeilen auf Englisch wie das Diagnose-Protokoll.
/// Identisch zu `ValidationReport` (Kotlin).
public enum ValidationReport {
    /// Titel des Abschnitts im Diagnose-Bericht.
    public static let title = "VALIDATION"

    /// Erste Zeile von ``lines(_:)``: was die Referenz ist und wie die Summen zu lesen sind.
    public static let note = "reference = sleep stages of the health app (estimate of the watch, not a sleep lab), "
        + "±1 min; "
        + "x/y = light, REM or awake of decisions with a known stage; target = DEADLINE, EXACT, SHORT_NIGHT"

    /// Kopfzeile von ``exportCsv(_:)``.
    public static let csvHeader = "night,time,kind,reason,ref_stage"

    /// Zählt die Referenzphasen von Entscheiden (`noReference` zählt nicht).
    public struct Tally: Equatable, Sendable {
        /// Anzahl je Referenzphase.
        public var awake = 0, light = 0, rem = 0, deep = 0, unknown = 0

        /// Leicht, REM oder wach: ein guter Moment.
        public var good: Int { awake + light + rem }
        /// Mit bekannter Phase.
        public var known: Int { good + deep }

        /// «gut/bekannt», dazu « unknown N» mit unbekannten; ganz ohne Entscheid «-».
        public func ratio() -> String {
            if known == 0 && unknown == 0 { return "-" }
            return unknown > 0 ? "\(good)/\(known) unknown \(unknown)" : "\(good)/\(known)"
        }

        /// Phasen mit Anzahl, z. B. «light 1 deep 2» (Reihenfolge awake, light, rem, deep, unknown); ohne «-».
        public func stages() -> String {
            let text = [("awake", awake), ("light", light), ("rem", rem), ("deep", deep), ("unknown", unknown)]
                .filter { $0.1 > 0 }.map { "\($0.0) \($0.1)" }.joined(separator: " ")
            return text.isEmpty ? "-" : text
        }

        /// Zählt die Referenzphasen von `entries`.
        public static func of(_ entries: [Validation.Entry]) -> Tally {
            var tally = Tally()
            for entry in entries {
                switch entry.ref {
                case .awake: tally.awake += 1
                case .light: tally.light += 1
                case .rem: tally.rem += 1
                case .deep: tally.deep += 1
                case .unknown: tally.unknown += 1
                case .noReference: break
                }
            }
            return tally
        }
    }

    /// Summen über `entries` mit Referenz: LIGHT_SLEEP echt und Schatten, Phase zur Zielzeit (``WakeReason/atTarget``)
    /// echt und Schatten, Trefferquote aller echten Entscheide.
    public static func summary(_ entries: [Validation.Entry]) -> String {
        let counted = entries.filter { $0.ref != .noReference }
        func tally(_ kind: Validation.Kind, _ filter: (WakeReason) -> Bool) -> Tally {
            Tally.of(counted.filter { $0.kind == kind && filter($0.reason) })
        }
        let lightReal = tally(.real) { $0 == .lightSleep }.ratio()
        let lightShadow = tally(.shadow) { $0 == .lightSleep }.ratio()
        let targetReal = tally(.real) { $0.atTarget }.stages()
        let targetShadow = tally(.shadow) { $0.atTarget }.stages()
        let hits = tally(.real) { _ in true }.ratio()
        return "LIGHT_SLEEP real \(lightReal) shadow \(lightShadow) · target real \(targetReal) shadow \(targetShadow)"
            + " · hits real \(hits)"
    }

    /// Zeilen des Abschnitts: ``note``, je Nacht ihre Entscheide «night N HH:mm kind REASON ref» und «night N: …» mit
    /// den Summen (``summary(_:)``) bzw. «night N: no reference», zuletzt «all: N nights, M with reference · …». Ohne
    /// Entscheide leer.
    public static func lines(_ entries: [Validation.Entry]) -> [String] {
        if entries.isEmpty { return [] }
        var nights: [Int] = []
        for entry in entries where nights.last != entry.night { nights.append(entry.night) }
        var out = [note]
        var withRef = 0
        for night in nights {
            let list = entries.filter { $0.night == night }
            for e in list {
                out.append("night \(night) \(time(e.at, e.timeZone)) \(e.kind.rawValue) \(e.reason.rawValue) "
                           + "\(e.ref.rawValue)")
            }
            if list.allSatisfy({ $0.ref == .noReference }) {
                out.append("night \(night): no reference")
            } else {
                withRef += 1
                out.append("night \(night): \(summary(list))")
            }
        }
        out.append("all: \(nights.count) nights, \(withRef) with reference"
                   + (withRef > 0 ? " · \(summary(entries))" : ""))
        return out
    }

    /// CSV «night,time,kind,reason,ref_stage», je Entscheid eine Zeile; Uhrzeit «HH:mm» ohne Datum.
    public static func exportCsv(_ entries: [Validation.Entry]) -> String {
        var text = csvHeader + "\n"
        for e in entries {
            text += "\(e.night),\(time(e.at, e.timeZone)),\(e.kind.rawValue),\(e.reason.rawValue),\(e.ref.rawValue)\n"
        }
        return text
    }

    /// «HH:mm» in `zone`, gregorianisch mit Ziffern 0–9 wie Kotlin.
    public static func time(_ at: Date, _ zone: TimeZone) -> String {
        let parts = Calendar.gregorian(in: zone).dateComponents([.hour, .minute], from: at)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }
}
