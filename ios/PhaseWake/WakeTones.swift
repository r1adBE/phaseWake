import AVFoundation
import Foundation

/// Eigene Wecktöne (wie Android): im Feld `sound` des Weckers als «raw:<Dateiname>» gespeichert; jeder
/// andere Wert (leer, oder die URI eines Android-Systemtons) = Standardton des Systems.
enum WakeTone: String, CaseIterable {
    case birds = "raw:wake_birds"
    case glockenspiel = "raw:wake_glockenspiel"
    case gong = "raw:wake_gong"
    case harp = "raw:wake_harp"

    /// Textschlüssel wie Android («wake_tone_birds» …).
    var textKey: String { "wake_tone_" + String(rawValue.dropFirst("raw:wake_".count)) }

    /// Datei im App-Bundle: IMA4, 28 s (unter der Grenze von 30 s für Wecker- und Mitteilungstöne).
    var fileName: String { String(rawValue.dropFirst("raw:".count)) + ".caf" }

    var url: URL? {
        let name = String(rawValue.dropFirst("raw:".count))
        return Bundle.main.url(forResource: name, withExtension: "caf")
            ?? Bundle.main.url(forResource: name, withExtension: "caf", subdirectory: "Sounds")
    }
}

/// Kurzes Probehören eines Wecktons im Editor (Medienlautstärke), nach 6 s oder mit `stop()` zu Ende.
@MainActor
final class WakeTonePreview {
    private var player: AVAudioPlayer?
    private var endTask: Task<Void, Never>?

    func play(_ tone: WakeTone) {
        stop()
        // Läuft eine Aufnahme oder spielen Klänge, teilt sich das Probehören die Sitzung mit ihnen.
        AudioSessionUse.previewing = true
        guard (try? AudioSessionUse.activate()) != nil, let url = tone.url, let player = try? AVAudioPlayer(contentsOf: url) else {
            AudioSessionUse.previewing = false
            AudioSessionUse.release()
            return
        }
        player.volume = 0.6
        // Die Töne blenden 8 s lang ein: hörbar erst danach.
        player.currentTime = 8
        guard player.play() else {
            AudioSessionUse.previewing = false
            AudioSessionUse.release()
            return
        }
        self.player = player
        endTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(6))
            if !Task.isCancelled { self.stop() }
        }
    }

    func stop() {
        endTask?.cancel()
        endTask = nil
        guard let player else { return }
        player.stop()
        self.player = nil
        AudioSessionUse.previewing = false
        AudioSessionUse.release()
    }
}
