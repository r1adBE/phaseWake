import Foundation
import PhaseWakeCore

extension PhaseWakeStorage {
    /// Speicher der Uhr-App: Wecker und Einstellungen in der App-Gruppe (die Komplikation liest sie über
    /// `watchShared`), Nächte im von der Sicherung ausgenommenen Ordner (`PrivateFiles`) wie auf dem iPhone. Die
    /// Sicherung der Uhr hängt an der des iPhones und ginge sonst mit Bewegung und Puls je Epoche in iCloud.
    /// Frühere Versionen legten `nights.v1` direkt in Application Support ab; `adopt` holt die Datei einmalig herüber.
    static let watch = PhaseWakeStorage(defaults: UserDefaults(suiteName: watchGroup) ?? UserDefaults.standard,
                                        directory: PrivateFiles.adopt(["nights.v1"]))
}
