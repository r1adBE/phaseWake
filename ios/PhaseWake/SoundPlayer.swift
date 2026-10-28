import AVFoundation
import Foundation
import Observation
import PhaseWakeCore

/// Einschlafklänge über AVAudioEngine: Der Klang entsteht laufend im Code (keine Datei, keine Schleife),
/// spielt als Medium (Medienlautstärke, nie als Wecker) und endet mit dem Timer, spätestens beim
/// Weckfenster. Eine Unterbrechung (Anruf, Wecker) beendet ihn.
@MainActor
@Observable
final class SoundPlayer {
    /// Was gerade spielt; `nil` = still.
    private(set) var playing: SleepSound?
    /// Wann die Klänge enden; `nil` = ohne Ende.
    private(set) var endsAt: Date?

    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var node: AVAudioSourceNode?
    @ObservationIgnored private var state: RenderState?
    @ObservationIgnored private var stopTask: Task<Void, Never>?
    /// Jede Wiedergabe hat eine eigene Nummer, damit ein spätes Aufräumen keine neue beendet.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private static let sampleRate = 48_000.0
    /// Kurzes Ausblenden beim Stoppen und Wechseln, damit es nicht knackt.
    private static let quickFade = 0.3

    init() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            guard raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began else { return }
            Task { @MainActor in self?.teardown() }
        })
        // Ausgabe gewechselt (Kopfhörer): Die Engine hält an und muss neu starten.
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.playing != nil, let engine = self.engine, !engine.isRunning else { return }
                do { try engine.start() } catch { self.teardown() }
            }
        })
    }

    /// Startet `sound`; läuft schon ein Klang, wird kurz übergeblendet. `nextWindowStart` = Beginn des
    /// nächsten Weckfensters: Dort ist spätestens Schluss.
    func play(_ sound: SleepSound, timerMinutes: Int?, nextWindowStart: Date?) {
        let now = Date()
        let end = SleepSounds.stopAt(start: now, timerMinutes: timerMinutes, nextWindowStart: nextWindowStart)
        if let end, end <= now { return }
        if playing != nil {
            fadeOutQuickly()
            let number = generation
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(Self.quickFade + 0.05))
                guard self.generation == number else { return }
                self.teardown()
                self.start(sound, end: end, now: Date())
            }
        } else {
            start(sound, end: end, now: now)
        }
    }

    /// Stoppt mit kurzem Ausblenden.
    func stop() {
        guard playing != nil else { return }
        fadeOutQuickly()
        playing = nil
        endsAt = nil
        let number = generation
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.quickFade + 0.05))
            if self.generation == number { self.teardown() }
        }
    }

    private func start(_ sound: SleepSound, end: Date?, now: Date) {
        guard let format = AVAudioFormat(standardFormatWithSampleRate: Self.sampleRate, channels: 1) else { return }
        // Medium, nicht mit anderen Apps gemischt; läuft die Geräusch-Aufnahme, teilen sich beide die Sitzung.
        AudioSessionUse.playing = true
        do {
            try AudioSessionUse.activate()
        } catch {
            AudioSessionUse.playing = false
            AudioSessionUse.release()
            return
        }
        let state = RenderState(
            generator: NoiseGenerator(sound: sound, sampleRate: Self.sampleRate, seed: UInt64(now.timeIntervalSince1970)),
            sampleRate: Self.sampleRate,
            totalFrames: end.map { Int64($0.timeIntervalSince(now) * Self.sampleRate) } ?? Int64.max
        )
        releaseEngine()
        let engine = AVAudioEngine()
        let node = Self.makeNode(state, format: format)
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            AudioSessionUse.playing = false
            AudioSessionUse.release()
            return
        }
        generation += 1
        self.engine = engine
        self.node = node
        self.state = state
        playing = sound
        endsAt = end
        stopTask?.cancel()
        if let end {
            let number = generation
            stopTask = Task { @MainActor in
                // Ausgeblendet ist bei `end` schon; danach aufräumen.
                try? await Task.sleep(for: .seconds(max(0, end.timeIntervalSinceNow) + 0.2))
                if !Task.isCancelled, self.generation == number { self.teardown() }
            }
        }
    }

    private func fadeOutQuickly() {
        state?.stopFrame = (state?.frame ?? 0) + Int64(Self.quickFade * Self.sampleRate)
    }

    /// Hält sofort an und gibt die Audio-Sitzung frei.
    func teardown() {
        generation += 1
        stopTask?.cancel()
        stopTask = nil
        releaseEngine()
        playing = nil
        endsAt = nil
        // Sitzung frei, ausser die Geräusch-Aufnahme läuft noch.
        AudioSessionUse.playing = false
        AudioSessionUse.release()
    }

    private func releaseEngine() {
        engine?.stop()
        if let node { engine?.detach(node) }
        engine = nil
        node = nil
        state = nil
    }

    /// Ausserhalb des Main Actors: Der Block läuft auf dem Audio-Thread.
    nonisolated private static func makeNode(_ state: RenderState, format: AVAudioFormat) -> AVAudioSourceNode {
        AVAudioSourceNode(format: format) { _, _, frameCount, audioBufferList -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let count = Int(frameCount)
            guard let first = buffers.first, let data = first.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            state.render(into: data, count: count)
            for buffer in buffers.dropFirst() {
                if let target = buffer.mData { memcpy(target, data, count * MemoryLayout<Float>.size) }
            }
            return noErr
        }
    }
}

/// Zustand des Audio-Threads. Der Main Actor setzt nur `stopFrame` (ein einzelner 64-Bit-Wert);
/// alles andere gehört dem Audio-Thread.
private final class RenderState: @unchecked Sendable {
    var generator: NoiseGenerator
    let sampleRate: Double
    var frame: Int64 = 0
    /// Spieldauer in Abtastwerten; `Int64.max` = ohne Ende.
    let totalFrames: Int64
    /// Stopp von Hand: bis hierhin kurz ausblenden; `Int64.max` = nicht gestoppt.
    var stopFrame = Int64.max
    private let quickFadeFrames: Double

    init(generator: NoiseGenerator, sampleRate: Double, totalFrames: Int64) {
        self.generator = generator
        self.sampleRate = sampleRate
        self.totalFrames = totalFrames
        quickFadeFrames = 0.3 * sampleRate
    }

    func render(into data: UnsafeMutablePointer<Float>, count: Int) {
        let total = totalFrames == Int64.max ? nil : Double(totalFrames) / sampleRate
        let stop = stopFrame
        for i in 0..<count {
            var gain = SleepSounds.gain(elapsed: Double(frame) / sampleRate, total: total)
            if stop != Int64.max { gain *= min(1, max(0, Double(stop - frame) / quickFadeFrames)) }
            data[i] = gain > 0 ? generator.next() * Float(gain) : 0
            frame += 1
        }
    }
}
