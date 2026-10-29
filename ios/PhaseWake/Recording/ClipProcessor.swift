import AVFoundation
import Foundation
import PhaseWakeCore

/// Verarbeitet das Mikrofon auf einer eigenen Warteschlange: Pegel je 100 ms, Erkennung, Vorlauf,
/// Ausschnitt schreiben (AAC 16 kHz) und einordnen. Zustand gehört allein der Warteschlange.
final class ClipProcessor: @unchecked Sendable {
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
