import AVFoundation
import Foundation
import Observation
import PhaseWakeCore
import SoundAnalysis
import UIKit

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
    private func restartIfNeeded() {
        guard running, engine?.isRunning != true else { return }
        stopEngine()
        if !startEngine() {
            // Neustart im Hintergrund nicht möglich: beim nächsten Wechsel erneut versuchen.
            try? AudioSessionUse.activate()
        }
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

/// Verarbeitet das Mikrofon auf einer eigenen Warteschlange: Pegel je 100 ms, Erkennung, Vorlauf,
/// Ausschnitt schreiben (AAC 16 kHz) und einordnen. Zustand gehört allein der Warteschlange.
private final class ClipProcessor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.phasewake.app.sounds")
    private let directory: URL
    private let sampleRate: Double
    private let startedAt: Date
    private let onClip: @Sendable (SoundClip) -> Void

    private let blockSize: Int
    private var pending: [Float] = []
    private var blockIndex: Int64 = 0
    private var detector = SoundEventDetector()
    /// Die letzten Abtastwerte für den Vorlauf (2 s vor dem Beginn, erkannt 0,5 s danach).
    private var ring: [Float]
    private var ringWrite = 0
    /// Anzahl aller bisher verarbeiteten Abtastwerte.
    private var written: Int64 = 0
    /// Laufender Ausschnitt: erster Abtastwert und Werte.
    private var captureStart: Int64?
    private var capture: [Float] = []
    /// Fertiger Ausschnitt, bis das Geräusch endet (dann ist seine Dauer bekannt).
    private var finished: (onsetMs: Int64, file: String, type: SoundType)?

    init(directory: URL, sampleRate: Double, startedAt: Date, onClip: @escaping @Sendable (SoundClip) -> Void) {
        self.directory = directory
        self.sampleRate = sampleRate
        self.startedAt = startedAt
        self.onClip = onClip
        blockSize = max(1, Int(sampleRate * Double(SoundEventDetector.frameMs) / 1000))
        ring = [Float](repeating: 0, count: Int(sampleRate * 3))
    }

    /// Vom Audio-Thread: Kanal 0 kopieren und weitergeben.
    func append(_ buffer: AVAudioPCMBuffer) {
        guard let data = buffer.floatChannelData?[0] else { return }
        let samples = Array(UnsafeBufferPointer(start: data, count: Int(buffer.frameLength)))
        queue.async { self.process(samples) }
    }

    func finish() {
        queue.async {
            let atMs = self.blockIndex * SoundEventDetector.frameMs
            self.handle(self.detector.finish(atMs: atMs))
        }
    }

    private func process(_ samples: [Float]) {
        var offset = 0
        while offset < samples.count {
            let take = min(blockSize - pending.count, samples.count - offset)
            pending.append(contentsOf: samples[offset..<(offset + take)])
            offset += take
            if pending.count == blockSize {
                block(pending)
                pending.removeAll(keepingCapacity: true)
            }
        }
    }

    private func block(_ samples: [Float]) {
        for s in samples {
            ring[ringWrite] = s
            ringWrite = (ringWrite + 1) % ring.count
        }
        written += Int64(samples.count)
        if captureStart != nil { capture.append(contentsOf: samples) }
        let level = samples.withUnsafeBufferPointer { SoundEvents.levelDb($0) }
        let atMs = blockIndex * SoundEventDetector.frameMs
        blockIndex += 1
        handle(detector.onFrame(atMs: atMs, levelDb: level))
    }

    private func sample(atMs ms: Int64) -> Int64 { ms * Int64(blockSize) / SoundEventDetector.frameMs }

    private func handle(_ actions: [SoundEventDetector.Action]) {
        for action in actions {
            switch action {
            case let .clipStart(clipStartMs, _):
                // Vorlauf aus dem Ringpuffer, soweit vorhanden.
                let from = max(sample(atMs: max(0, clipStartMs)), written - Int64(ring.count))
                captureStart = from
                capture = recent(Int(written - from))
            case let .clipEnd(onsetMs, clipEndMs):
                guard let start = captureStart else { continue }
                let length = Int(max(0, min(Int64(capture.count), sample(atMs: clipEndMs) - start)))
                let samples = Array(capture.prefix(length))
                captureStart = nil
                capture = []
                finished = writeAndClassify(samples, onsetMs: onsetMs)
            case let .eventEnd(onsetMs, durationMs, peakDb):
                guard let clip = finished, clip.onsetMs == onsetMs else { continue }
                finished = nil
                let at = startedAt.addingTimeInterval(TimeInterval(onsetMs) / 1000)
                onClip(SoundClip(night: SoundEvents.nightDate(at, calendar: .current), at: at, type: clip.type,
                                 durationMs: durationMs, peakDb: Int(peakDb.rounded()), file: clip.file))
            }
        }
    }

    /// Die letzten `count` Werte aus dem Ringpuffer, älteste zuerst.
    private func recent(_ count: Int) -> [Float] {
        let n = min(count, ring.count)
        return (0..<n).map { ring[(ringWrite - n + $0 + ring.count) % ring.count] }
    }

    private func writeAndClassify(_ samples: [Float], onsetMs: Int64) -> (onsetMs: Int64, file: String, type: SoundType)? {
        guard !samples.isEmpty else { return nil }
        let at = startedAt.addingTimeInterval(TimeInterval(onsetMs) / 1000)
        let file = "clip-\(Int64((at.timeIntervalSince1970 * 1000).rounded(.down))).m4a"
        let url = directory.appendingPathComponent(file)
        guard ClipFile.write(samples, sampleRate: sampleRate, to: url) else { return nil }
        return (onsetMs, file, ClipFile.classify(url))
    }
}

/// Ausschnitt schreiben und einordnen.
private enum ClipFile {
    /// Leise Ausschnitte werden angehoben (höchstens +30 dB), damit man sie morgens hört.
    private static let maxGain: Float = 31.6
    private static let targetPeak: Float = 0.7

    /// Auf 16 kHz mono umrechnen und als AAC (.m4a) speichern.
    static func write(_ samples: [Float], sampleRate: Double, to url: URL) -> Bool {
        let rate = Double(SoundEvents.sampleRate)
        guard let inFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1, interleaved: false),
              let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1, interleaved: false),
              let input = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: AVAudioFrameCount(samples.count)),
              let converter = AVAudioConverter(from: inFormat, to: outFormat),
              let output = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: AVAudioFrameCount(Double(samples.count) * rate / sampleRate) + 4096),
              let target = input.floatChannelData?[0] else { return false }
        let peak = samples.reduce(Float(0)) { max($0, abs($1)) }
        let gain = peak > 0 ? min(maxGain, targetPeak / peak) : 1
        for i in samples.indices { target[i] = samples[i] * gain }
        input.frameLength = AVAudioFrameCount(samples.count)
        var fed = false
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            if fed {
                inputStatus.pointee = .endOfStream
                return nil
            }
            fed = true
            inputStatus.pointee = .haveData
            return input
        }
        guard status != .error, output.frameLength > 0 else { return false }
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: rate,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 24_000,
        ]
        do {
            // Die Datei wird geschlossen, sobald `file` den Block verlässt.
            let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
            try file.write(from: output)
        } catch {
            try? FileManager.default.removeItem(at: url)
            return false
        }
        return true
    }

    /// Einordnung mit dem eingebauten Klassifikator (SoundAnalysis, auf dem Gerät): je Bezeichnung der
    /// Höchstwert über den Ausschnitt. Klappt das nicht: «anderes» (der Ausschnitt bleibt).
    static func classify(_ url: URL) -> SoundType {
        do {
            let request = try SNClassifySoundRequest(classifierIdentifier: .version1)
            let analyzer = try SNAudioFileAnalyzer(url: url)
            let observer = ClipObserver()
            try analyzer.add(request, withObserver: observer)
            analyzer.analyze()
            return SoundEvents.classify(observer.scores)
        } catch {
            return .other
        }
    }
}

private final class ClipObserver: NSObject, SNResultsObserving {
    var scores: [String: Double] = [:]

    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let result = result as? SNClassificationResult else { return }
        for item in result.classifications {
            scores[item.identifier] = max(scores[item.identifier] ?? 0, item.confidence)
        }
    }
}
