import AVFoundation
import Foundation
import Observation
import PhaseWakeCore
import UIKit

/// Geräusche in der Nacht (Schnarchen, Sprechen): Nur auf Wunsch, nur auf dem iPhone. Das Mikrofon läuft
/// über AVAudioEngine bis 30 Minuten nach dem nächsten Wecker (Hintergrund-Modus «audio»); kurze
/// Ausschnitte (AAC, 16 kHz) bleiben im App-Ordner, nicht im Backup, nicht im Export, und werden nach
/// 7 Tagen gelöscht. Die Einordnung macht der eingebaute Klassifikator von SoundAnalysis auf dem Gerät.
///
/// Eigene Einschlafklänge werden nicht herausgerechnet: Sie heben das Grundrauschen, der Median der
/// letzten 60 s passt sich an (gleichmässiges Rauschen löst kein Geräusch aus).
@MainActor
@Observable
final class SoundRecorder {
    /// Ob gerade aufgenommen wird.
    private(set) var running = false
    /// Wann die Aufnahme von selbst endet.
    private(set) var stopAt: Date?
    /// Alle gespeicherten Ausschnitte (höchstens 7 Tage).
    private(set) var clips: [SoundClip] = []
    /// Mikrofon verweigert: Hinweis mit Weg in die Einstellungen.
    private(set) var permissionDenied = false

    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var processor: ClipProcessor?
    @ObservationIgnored private var stopTask: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    /// Ordner der Ausschnitte, vom Backup ausgenommen.
    nonisolated static let directory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        var dir = base.appendingPathComponent("SoundClips", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir
    }()

    private static var indexURL: URL { directory.appendingPathComponent("clips.txt") }

    init() {
        clips = SoundEvents.decode(try? String(contentsOf: Self.indexURL, encoding: .utf8))
        expire()
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .ended else { return }
            // Nach einem Anruf oder Wecker weiter aufnehmen, falls die Nacht noch läuft.
            Task { @MainActor in self?.restartIfNeeded() }
        })
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.restartIfNeeded() }
        })
    }

    /// Ausschnitte einer Nacht, älteste zuerst.
    func nightClips(_ night: String) -> [SoundClip] { SoundEvents.forNight(clips, night: night) }

    func url(of clip: SoundClip) -> URL? {
        SoundEvents.isSafeFile(clip.file) ? Self.directory.appendingPathComponent(clip.file) : nil
    }

    // MARK: Aufnahme

    /// Startet die Aufnahme bis 30 Minuten nach `nextAlarmTarget` (ohne Wecker höchstens 12 Std.).
    /// Fragt beim ersten Mal nach dem Mikrofon. - Returns: ob sie läuft.
    @discardableResult
    func start(nextAlarmTarget: Date?) async -> Bool {
        if running { return true }
        guard await Self.requestPermission() else {
            permissionDenied = true
            return false
        }
        permissionDenied = false
        let now = Date()
        let end = SoundEvents.recordingEnd(start: now, nextTarget: nextAlarmTarget)
        AudioSessionUse.recording = true
        guard startEngine() else {
            AudioSessionUse.recording = false
            AudioSessionUse.release()
            return false
        }
        running = true
        stopAt = end
        expire()
        UIDevice.current.isBatteryMonitoringEnabled = true
        stopTask?.cancel()
        stopTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(max(0, end.timeIntervalSinceNow)))
            if !Task.isCancelled { self.stop() }
        }
        return true
    }

    /// Mikrofon schon erlaubt (ohne zu fragen).
    var permissionGranted: Bool { AVAudioApplication.shared.recordPermission == .granted }

    static func requestPermission() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied: return false
        default: return await AVAudioApplication.requestRecordPermission()
        }
    }

    func stop() {
        guard running else { return }
        stopTask?.cancel()
        stopTask = nil
        stopEngine()
        running = false
        stopAt = nil
        UIDevice.current.isBatteryMonitoringEnabled = false
        AudioSessionUse.recording = false
        AudioSessionUse.release()
    }

    /// Ob das iPhone lädt (sonst der Hinweis, es einzustecken).
    var charging: Bool {
        let state = UIDevice.current.batteryState
        return state == .charging || state == .full
    }

    private func startEngine() -> Bool {
        do { try AudioSessionUse.activate() } catch { return false }
        let engine = AVAudioEngine()
        let format = engine.inputNode.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { return false }
        let processor = ClipProcessor(directory: Self.directory, sampleRate: format.sampleRate, startedAt: Date()) { [weak self] result in
            Task { @MainActor in self?.store(result) }
        }
        Self.installTap(on: engine.inputNode, format: format, processor: processor)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            engine.inputNode.removeTap(onBus: 0)
            return false
        }
        self.engine = engine
        self.processor = processor
        return true
    }

    private func stopEngine() {
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        engine = nil
        // Ein laufendes Geräusch wird abgeschlossen und gespeichert.
        processor?.finish()
        processor = nil
    }

    /// Nach Unterbrechung oder Wechsel des Mikrofons neu starten; die Zeit läuft mit einem neuen Bezug weiter.
    /// Klappt das nicht (im Hintergrund oft nicht erlaubt), endet die Aufnahme sichtbar, statt scheinbar
    /// weiterzulaufen; beim nächsten Öffnen der App startet sie wieder, wenn sie für diese Nacht gewünscht ist.
    private func restartIfNeeded() {
        guard running, engine?.isRunning != true else { return }
        stopEngine()
        if !startEngine() { stop() }
    }

    /// Ausserhalb des Main Actors: Der Block läuft auf dem Audio-Thread.
    nonisolated private static func installTap(on input: AVAudioInputNode, format: AVAudioFormat, processor: ClipProcessor) {
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in
            processor.append(buffer)
        }
    }

    // MARK: Speicher

    private func store(_ clip: SoundClip) {
        let result = SoundEvents.add(clips, clip)
        delete(result.dropped.map(\.file))
        save(result.kept)
    }

    func delete(_ clip: SoundClip) {
        delete([clip.file])
        save(clips.filter { $0.file != clip.file })
    }

    func deleteAll(night: String) {
        let gone = clips.filter { $0.night == night }
        delete(gone.map(\.file))
        save(clips.filter { $0.night != night })
    }

    /// Löscht alle Ausschnitte (mit dem Verlauf).
    func deleteEverything() {
        delete(clips.map(\.file))
        save([])
    }

    /// Ausschnitte älter als 7 Tage weg (beim Start der App und jeder Aufnahme, sonst täglich beim Öffnen).
    func expire(now: Date = Date()) {
        let result = SoundEvents.expire(clips, now: now)
        guard !result.expired.isEmpty else { return }
        delete(result.expired.map(\.file))
        save(result.kept)
    }

    private func delete(_ files: [String]) {
        for file in files where SoundEvents.isSafeFile(file) {
            try? FileManager.default.removeItem(at: Self.directory.appendingPathComponent(file))
        }
    }

    private func save(_ list: [SoundClip]) {
        clips = SoundEvents.decode(SoundEvents.encode(list))
        try? Data(SoundEvents.encode(list).utf8).write(to: Self.indexURL, options: .atomic)
    }
}
