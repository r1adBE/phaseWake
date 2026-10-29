import AVFoundation

/// Gemeinsame Audio-Sitzung für Einschlafklänge und Geräusch-Aufnahme: Läuft die Aufnahme, gilt
/// «Wiedergabe und Aufnahme» (Klänge über den Lautsprecher), sonst «Wiedergabe». Die Sitzung wird erst
/// freigegeben, wenn beides aus ist.
@MainActor
enum AudioSessionUse {
    /// Einschlafklänge spielen.
    static var playing = false
    /// Ein Ausschnitt wird angehört.
    static var previewing = false
    static var recording = false

    static func activate() throws {
        let session = AVAudioSession.sharedInstance()
        if recording {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothA2DP])
        } else {
            // Medium, nicht mit anderen Apps gemischt.
            try session.setCategory(.playback, mode: .default, options: [])
        }
        try session.setActive(true)
    }

    /// Nach dem Ende von Klängen oder Aufnahme: auf den verbleibenden Zweck umstellen oder freigeben.
    static func release() {
        if playing || previewing || recording {
            try? activate()
        } else {
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}
