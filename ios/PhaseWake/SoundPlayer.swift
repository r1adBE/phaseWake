import AVFoundation
import Foundation
import Observation
import PhaseWakeCore

/// Einschlafklänge über AVAudioEngine: Rauschen, Ventilator und Einschlaf-Wellen entstehen laufend im Code;
/// Regen, Meer, Wald, Feuer und Bach sind Aufnahmen (CC0), einmal in den Speicher gelesen und nahtlos in
/// Schleife gespielt (`LoopedRecording`). Beides läuft durch denselben Quellknoten mit denselben Blenden,
/// spielt als Medium (Medienlautstärke, nie als Wecker) und endet mit dem Timer, spätestens beim
/// Weckfenster. Eine Unterbrechung (Anruf, Wecker) beendet ihn, ebenso das Klingeln der Uhr
/// (`PhoneModel.watchRang`). Das Ende wird jede Minute mit dem aktuellen Plan verglichen (wie Android).
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
    /// Beginn des nächsten Weckfensters laut aktuellem Plan (setzt `PhoneModel`); jede Minute abgefragt.
    @ObservationIgnored var currentWindowStart: () -> Date? = { nil }
    @ObservationIgnored private var recheckTask: Task<Void, Never>?
    @ObservationIgnored private var startedAt: Date?

    /// Abtastrate der erzeugten Klänge; Aufnahmen behalten ihre eigene (der Mixer rechnet um).
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
        // Aufnahme wird noch gelesen: sofort aufräumen (das Lesen verfällt dann).
        if state == nil {
            teardown()
            return
        }
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
        guard sound.recorded else {
            begin(sound, recording: nil, end: end, now: now)
            return
        }
        // Aufnahme ausserhalb des Main Actors lesen (bis etwa 1 s); die Oberfläche zeigt schon «Stopp».
        generation += 1
        let number = generation
        playing = sound
        endsAt = end
        Task { @MainActor in
            let recording = await Task.detached(priority: .userInitiated) { LoopedRecording.load(sound) }.value
            guard self.generation == number else { return }
            guard let recording else {
                self.teardown()
                return
            }
            self.begin(sound, recording: recording, end: end, now: Date())
        }
    }

    private func begin(_ sound: SleepSound, recording: LoopedRecording?, end: Date?, now: Date) {
        let rate = recording?.buffer.format.sampleRate ?? Self.sampleRate
        let generator = recording == nil ? NoiseGenerator(sound: sound, sampleRate: rate, seed: UInt64(now.timeIntervalSince1970)) : nil
        let channels = recording.map { Int($0.buffer.format.channelCount) } ?? generator?.channels ?? 1
        guard let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: AVAudioChannelCount(channels)) else {
            teardown()
            return
        }
        // Medium, nicht mit anderen Apps gemischt; läuft die Geräusch-Aufnahme, teilen sich beide die Sitzung.
        AudioSessionUse.playing = true
        do {
            try AudioSessionUse.activate()
        } catch {
            AudioSessionUse.playing = false
            AudioSessionUse.release()
            playing = nil
            endsAt = nil
            return
        }
        let state = RenderState(
            generator: generator,
            recording: recording,
            channels: channels,
            sampleRate: rate,
            totalFrames: end.map { Int64($0.timeIntervalSince(now) * rate) } ?? Int64.max,
            boost: sound.gain
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
            playing = nil
            endsAt = nil
            return
        }
        generation += 1
        self.engine = engine
        self.node = node
        self.state = state
        playing = sound
        endsAt = end
        startedAt = now
        scheduleTeardown(at: end)
        let number = generation
        recheckTask?.cancel()
        recheckTask = Task { @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                guard !Task.isCancelled, self.generation == number else { return }
                self.shortenToWindow()
            }
        }
    }

    /// Räumt nach dem Ende auf (ausgeblendet ist dann schon).
    private func scheduleTeardown(at end: Date?) {
        stopTask?.cancel()
        stopTask = nil
        guard let end else { return }
        let number = generation
        stopTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(max(0, end.timeIntervalSinceNow) + 0.2))
            if !Task.isCancelled, self.generation == number { self.teardown() }
        }
    }

    /// Beginnt das nächste Weckfenster laut aktuellem Plan früher als das bisherige Ende (Wecker später
    /// gestellt oder geändert), endet der Klang dort. Nur kürzen, nie verlängern. Bleibt dafür weniger als
    /// die Ausblendzeit, kurz ausblenden und stoppen (sonst spränge die Lautstärke).
    private func shortenToWindow() {
        guard playing != nil, let state, let startedAt, let window = currentWindowStart() else { return }
        let now = Date()
        let end = max(window, now)
        if let endsAt, end >= endsAt { return }
        guard end.timeIntervalSince(now) > SleepSounds.fadeOutSeconds else {
            stop()
            return
        }
        state.totalFrames = Int64(end.timeIntervalSince(startedAt) * state.sampleRate)
        endsAt = end
        scheduleTeardown(at: end)
    }

    private func fadeOutQuickly() {
        guard let state else { return }
        state.stopFrame = state.frame + Int64(Self.quickFade * state.sampleRate)
    }

    /// Hält sofort an und gibt die Audio-Sitzung frei.
    func teardown() {
        generation += 1
        stopTask?.cancel()
        stopTask = nil
        recheckTask?.cancel()
        recheckTask = nil
        startedAt = nil
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
            // Ohne Speicheranforderung auf dem Audio-Thread: höchstens zwei Kanäle (Mono oder Stereo).
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard let left = buffers.first?.mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            let right = buffers.count > 1 ? buffers[1].mData?.assumingMemoryBound(to: Float.self) : nil
            state.render(left: left, right: right, count: Int(frameCount))
            return noErr
        }
    }
}

/// Zustand des Audio-Threads. Der Main Actor setzt nur `stopFrame` und beim Kürzen `totalFrames`
/// (je ein einzelner 64-Bit-Wert); alles andere gehört dem Audio-Thread.
private final class RenderState: @unchecked Sendable {
    /// Genau eins von beiden: erzeugter Klang oder Aufnahme.
    private var generator: NoiseGenerator?
    private let recording: LoopedRecording?
    private let channels: Int
    let sampleRate: Double
    var frame: Int64 = 0
    /// Spieldauer in Abtastzeitpunkten; `Int64.max` = ohne Ende. Wird nur gekürzt (früheres Weckfenster).
    var totalFrames: Int64
    /// Stopp von Hand: bis hierhin kurz ausblenden; `Int64.max` = nicht gestoppt.
    var stopFrame = Int64.max
    private let quickFadeFrames: Double
    /// Lesestelle in der Aufnahme.
    private var position = 0
    /// Verstärkung der Aufnahme (`SleepSound.gain`); erzeugte Klänge 1.
    private let boost: Float

    init(generator: NoiseGenerator?, recording: LoopedRecording?, channels: Int, sampleRate: Double, totalFrames: Int64, boost: Float = 1) {
        self.generator = generator
        self.recording = recording
        self.channels = channels
        self.sampleRate = sampleRate
        self.totalFrames = totalFrames
        quickFadeFrames = 0.3 * sampleRate
        position = recording?.start ?? 0
        self.boost = boost
    }

    /// Füllt `count` Werte je Kanal (getrennte Kanäle wie im Standardformat). Mono-Quelle mit zweitem
    /// Ausgabekanal: beide gleich.
    func render(left: UnsafeMutablePointer<Float>, right: UnsafeMutablePointer<Float>?, count: Int) {
        let total = totalFrames == Int64.max ? nil : Double(totalFrames) / sampleRate
        let stop = stopFrame
        for i in 0..<count {
            var gain = SleepSounds.gain(elapsed: Double(frame) / sampleRate, total: total)
            if stop != Int64.max { gain *= min(1, max(0, Double(stop - frame) / quickFadeFrames)) }
            let g = Float(gain)
            var l: Float = 0
            var r: Float = 0
            if let recording {
                if position >= recording.end { position = recording.loopStart }
                // Aufnahmen sind leiser als die erzeugten Klänge: Verstärkung aus dem Kern, begrenzt.
                l = SleepSounds.amplify(recording.data[0][position], gain: boost) * g
                r = channels > 1 ? SleepSounds.amplify(recording.data[1][position], gain: boost) * g : l
                position += 1
            } else if generator != nil {
                // Verschachtelt erzeugt: bei zwei Kanälen zuerst links, dann rechts.
                l = generator!.next() * g
                r = channels > 1 ? generator!.next() * g : l
            }
            left[i] = l
            right?[i] = r
            frame += 1
        }
    }
}

/// Aufnahme im Speicher, bereit für eine Schleife ohne Lücke (wie Android `LoopedRecording`): Der erste
/// Durchgang beginnt bei `start`, jeder weitere bei `loopStart`; `end` ist exklusiv (Abtastzeitpunkte).
/// Nach dem Laden nur noch gelesen.
final class LoopedRecording: @unchecked Sendable {
    let buffer: AVAudioPCMBuffer
    let data: UnsafePointer<UnsafeMutablePointer<Float>>
    let start: Int
    let loopStart: Int
    let end: Int

    /// Stille am Rand (Vorlauf des AAC-Decoders) bis zu diesem Betrag wird abgeschnitten (4 von 32768).
    private static let silence: Float = 4 / 32_768

    private init(buffer: AVAudioPCMBuffer, data: UnsafePointer<UnsafeMutablePointer<Float>>, start: Int, loopStart: Int, end: Int) {
        self.buffer = buffer
        self.data = data
        self.start = start
        self.loopStart = loopStart
        self.end = end
    }

    /// Liest «sound_<name>.m4a» aus dem App-Bundle ganz ein; `nil`, wenn die Datei fehlt oder nicht lesbar ist.
    /// Schneidet stille Ränder ab und blendet das Ende 20 ms gleich laut (Kosinus/Sinus) in den Anfang über,
    /// falls der Decoder die Ränder nicht genau liefert.
    static func load(_ sound: SleepSound) -> LoopedRecording? {
        let name = "sound_" + sound.rawValue.lowercased()
        // Der synchronisierte Ordner legt die Dateien flach ins Bundle; zur Sicherheit auch im Unterordner suchen.
        guard let url = Bundle.main.url(forResource: name, withExtension: "m4a")
                ?? Bundle.main.url(forResource: name, withExtension: "m4a", subdirectory: "Sounds"),
              let file = try? AVAudioFile(forReading: url),
              let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)),
              (try? file.read(into: buffer)) != nil,
              let data = buffer.floatChannelData else { return nil }
        let channels = Int(buffer.format.channelCount)
        let frames = Int(buffer.frameLength)
        func silent(_ f: Int) -> Bool { (0..<channels).allSatisfy { abs(data[$0][f]) <= Self.silence } }
        var first = 0
        while first < frames && silent(first) { first += 1 }
        var last = frames - 1
        while last > first && silent(last) { last -= 1 }
        let end = last + 1
        guard end - first > Int(buffer.format.sampleRate) else { return nil }
        let seam = min(Int(buffer.format.sampleRate * 0.02), (end - first) / 4)
        for f in 0..<seam {
            let t = (Double(f) + 0.5) / Double(seam)
            let fadeOut = Float(cos(t * Double.pi / 2))
            let fadeIn = Float(sin(t * Double.pi / 2))
            for c in 0..<channels {
                let i = end - seam + f
                data[c][i] = data[c][i] * fadeOut + data[c][first + f] * fadeIn
            }
        }
        return LoopedRecording(buffer: buffer, data: data, start: first, loopStart: first + seam, end: end)
    }
}
