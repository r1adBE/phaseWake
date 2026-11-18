import AVFoundation
import Foundation
import PhaseWakeCore
import SoundAnalysis

/// Ausschnitt schreiben und einordnen.
enum ClipFile {
    /// Leise Ausschnitte werden angehoben (höchstens +30 dB), damit man sie morgens hört.
    private static let maxGain: Float = 31.6
    private static let targetPeak: Float = 0.7

    /// Auf 16 kHz mono umrechnen und als AAC (.m4a) speichern.
    static func write(_ samples: [Float], sampleRate: Double, to url: URL) -> Bool {
        let rate = Double(SoundEvents.sampleRate)
        guard let inFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: 1,
                                           interleaved: false),
              let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1,
                                            interleaved: false),
              let input = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: AVAudioFrameCount(samples.count)),
              let converter = AVAudioConverter(from: inFormat, to: outFormat),
              let output = AVAudioPCMBuffer(pcmFormat: outFormat,
                                            frameCapacity:
                                                AVAudioFrameCount(Double(samples.count) * rate / sampleRate) + 4096),
              let target = input.floatChannelData?[0] else { return false }
        let peak = samples.reduce(Float(0)) { max($0, abs($1)) }
        let gain = peak > 0 ? min(maxGain, targetPeak / peak) : 1
        for i in samples.indices { target[i] = samples[i] * gain }
        input.frameLength = AVAudioFrameCount(samples.count)
        let feed = ClipFeed(input)
        var error: NSError?
        let status = converter.convert(to: output, error: &error) { _, inputStatus in
            guard let buffer = feed.next() else {
                inputStatus.pointee = .endOfStream
                return nil
            }
            inputStatus.pointee = .haveData
            return buffer
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
            let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32,
                                       interleaved: false)
            try file.write(from: output)
        } catch {
            try? FileManager.default.removeItem(at: url)
            return false
        }
        return true
    }

    /// Einordnung mit dem eingebauten Klassifikator (SoundAnalysis, auf dem Gerät): Werte je Bezeichnung
    /// über alle Abschnitte gemittelt (`SoundEvents.averageScores`), dann `SoundEvents.classify` – wie
    /// Android. Klappt das nicht: «anderes» (der Ausschnitt bleibt).
    static func classify(_ url: URL) -> SoundType {
        do {
            let request = try SNClassifySoundRequest(classifierIdentifier: .version1)
            let analyzer = try SNAudioFileAnalyzer(url: url)
            let observer = ClipObserver()
            try analyzer.add(request, withObserver: observer)
            analyzer.analyze()
            return SoundEvents.classify(SoundEvents.averageScores(observer.frames))
        } catch {
            return .other
        }
    }
}

/// Gibt den Ausschnitt dem Wandler genau einmal, danach `nil` (Ende). `convert` ruft den Block synchron im selben
/// Aufruf; `@unchecked Sendable`, weil der Block als `@Sendable` gilt.
private final class ClipFeed: @unchecked Sendable {
    private var buffer: AVAudioPCMBuffer?

    init(_ buffer: AVAudioPCMBuffer) { self.buffer = buffer }

    func next() -> AVAudioPCMBuffer? {
        defer { buffer = nil }
        return buffer
    }
}

private final class ClipObserver: NSObject, SNResultsObserving {
    /// Werte je Bezeichnung, ein Eintrag je Abschnitt (Analysefenster).
    var frames: [[String: Double]] = []

    func request(_ request: SNRequest, didProduce result: SNResult) {
        guard let result = result as? SNClassificationResult else { return }
        var scores: [String: Double] = [:]
        for item in result.classifications { scores[item.identifier] = item.confidence }
        frames.append(scores)
    }
}
