import Foundation
import PhaseWakeCore

extension SyncKeys {
    /// Uhr → iPhone im Anwendungskontext: Build-Nummer der Uhr-App (``BuildNumber``). Fehlt bei lokalem Build und bei
    /// älteren Uhr-Apps; dann kein Hinweis «verschiedene Testversionen».
    static let watchBuild = "watchBuild"
}

/// Build-Nummer der Testversion (`CURRENT_PROJECT_VERSION`, aus der CI die Laufnummer) für «Über», Diagnose und
/// Nacht-Check, wie `BuildNumber` auf Android (Entscheid r1AD 14.11.2026). Einzige Stelle der Apple-Apps, die sie liest
/// (docs/ENTWICKLUNG.md, Abschnitt 3). Lokal gebaut ist sie 1 und gilt als unbekannt.
enum BuildNumber {
    /// Nummer eines Builds ohne Laufnummer der CI.
    static let local = 1

    /// Nummer aus der Bundle-Version `text`; `nil` bei lokalem Build oder unlesbar.
    static func of(_ text: String?) -> Int? {
        guard let text, let number = Int(text.trimmingCharacters(in: .whitespaces)), number > local else { return nil }
        return number
    }

    /// «16.2.2 (96)»; ohne bekannte Nummer nur «16.2.2».
    static func label(version: String, build: Int?) -> String {
        guard let build else { return version }
        return "\(version) (\(build))"
    }

    /// Ob iPhone und Uhr verschiedene Testversionen haben; ist eine Nummer unbekannt, nein.
    static func differs(phone: Int?, watch: Int?) -> Bool {
        guard let phone, let watch else { return false }
        return phone != watch
    }

    /// Eigene Nummer dieser App; `nil`, wenn unbekannt.
    static var own: Int? { of(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) }

    /// Eigene Versionsbezeichnung mit Nummer, z. B. «16.2.2 (96)».
    static var ownLabel: String {
        label(version: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "", build: own)
    }

    /// Uhr: Stand der Verbindung (``WatchProtocol``) und Build-Nummer in den Anwendungskontext ans iPhone; ohne
    /// bekannte Nummer fehlt sie.
    static func applyWatch(to context: inout [String: Any], build: Int? = BuildNumber.own) {
        context[SyncKeys.watchProtocol] = Int64(WatchProtocol.current)
        if let build {
            context[SyncKeys.watchBuild] = Int64(build)
        } else {
            context[SyncKeys.watchBuild] = nil
        }
    }

    /// iPhone: Build-Nummer der Uhr aus ihrem Anwendungskontext; `nil` ohne Angabe (ältere Uhr, lokaler Build).
    static func watchBuild(in context: [String: Any]) -> Int? {
        (context[SyncKeys.watchBuild] as? Int64).flatMap { $0 > Int64(local) ? Int(exactly: $0) : nil }
    }
}
