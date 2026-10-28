import Foundation

/// Art eines Geräuschs in der Nacht. Rohwerte wie die Kotlin-Namen.
public enum SoundType: String, CaseIterable, Sendable {
    case snore = "SNORE", talk = "TALK", other = "OTHER"
}

/// Ein aufgenommener Ausschnitt eines Geräuschs. Identisch zu `SoundClip` (Kotlin).
public struct SoundClip: Equatable, Sendable, Identifiable {
    /// Nachtdatum (wie bei den Notizen: Abend, an dem die Nacht beginnt).
    public let night: String
    /// Beginn des Geräuschs.
    public let at: Date
    public let type: SoundType
    /// Dauer des Geräuschs (kann länger sein als der Ausschnitt, z. B. minutenlanges Schnarchen).
    public let durationMs: Int64
    /// Lautester Pegel in dBFS (gerundet); bei zu vielen Ausschnitten bleiben die lautesten.
    public let peakDb: Int
    /// Dateiname des Ausschnitts im App-Ordner (ohne Pfad).
    public let file: String

    public var id: String { file }

    public init(night: String, at: Date, type: SoundType, durationMs: Int64, peakDb: Int, file: String) {
        self.night = night
        self.at = at
        self.type = type
        self.durationMs = durationMs
        self.peakDb = peakDb
        self.file = file
    }
}

/// Bilanz einer Nacht: «Schnarchen: 12 Min (8 Ausschnitte) · Sprechen: 2 Ausschnitte».
public struct SoundSummary: Equatable, Sendable {
    public let snoreMs: Int64
    public let snoreClips: Int
    public let talkClips: Int
    public let otherClips: Int

    public init(snoreMs: Int64, snoreClips: Int, talkClips: Int, otherClips: Int) {
        self.snoreMs = snoreMs
        self.snoreClips = snoreClips
        self.talkClips = talkClips
        self.otherClips = otherClips
    }

    /// Schnarch-Minuten, gerundet; kurzes Schnarchen zählt als 1 Min statt 0.
    public var snoreMinutes: Int { snoreMs <= 0 ? 0 : max(1, Int((Double(snoreMs) / 60_000).rounded())) }

    public var clips: Int { snoreClips + talkClips + otherClips }
}

/// Geräusche in der Nacht (Schnarchen, Sprechen): Regeln, Speicherformat und Aufräumen. Die Aufnahme
/// läuft nur auf Wunsch auf dem Handy; Ausschnitte bleiben im App-Ordner und werden nach 7 Tagen
/// gelöscht. Nie in Sicherungen, nie im Export, nie übertragen.
/// Speicherformat: je Ausschnitt eine Zeile «yyyy-MM-dd|Beginn ms|Art|Dauer ms|Spitze dB|Datei».
/// Identisch zu `SoundEvents` (Kotlin).
public enum SoundEvents {
    public static let retentionDays = 7
    public static let maxClipsPerNight = 40
    /// Die Aufnahme endet so lange nach der Zielzeit des nächsten Weckers.
    public static let afterTarget: TimeInterval = 30 * 60
    /// Ohne Wecker in dieser Zeit endet die Aufnahme spätestens nach so langer Zeit.
    public static let maxRun: TimeInterval = 12 * 3600
    /// Ohne Wecker in den nächsten 24 Std. startet die Vorgabe «für neue Nächte» ab dieser Stunde (Ortszeit).
    public static let autoStartHour = 21
    /// So weit voraus zählt ein Wecker für den automatischen Start.
    public static let autoStartLookahead: TimeInterval = 24 * 3600
    /// Abtastrate der gespeicherten Ausschnitte.
    public static let sampleRate = 16_000
    public static let silenceDb = -140.0

    /// Ende der Aufnahme: Zielzeit des nächsten Weckers + 30 Min; liegt kein Wecker innerhalb von 12 Std.
    /// (oder keiner), spätestens `start` + 12 Std.
    public static func recordingEnd(start: Date, nextTarget: Date?) -> Date {
        let latest = start.addingTimeInterval(maxRun)
        if let nextTarget, nextTarget > start, nextTarget <= latest { return nextTarget.addingTimeInterval(afterTarget) }
        return latest
    }

    /// Ob die Vorgabe «für neue Nächte ein» die Aufnahme beim Öffnen der App jetzt von selbst starten darf:
    /// ab der Bettzeit der kommenden Nacht (`Bedtime.bedtime` = Zielzeit des nächsten Weckers − Schlafziel)
    /// bis zur Zielzeit. Liegt kein Wecker innerhalb von 24 Std. (oder keiner), ab 21 Uhr bis Mitternacht.
    /// Davor startet sie nur, wenn der Nutzer sie selbst einschaltet. Identisch zu Kotlin `mayAutoStart`.
    /// `nextTarget` = Zielzeit des nächsten Weckers (Einschlaf-Modus: die eingestellte späteste Zeit).
    public static func mayAutoStart(now: Date, nextTarget: Date?, goalMinutes: Int, calendar: Calendar) -> Bool {
        if let nextTarget, nextTarget > now, nextTarget <= now.addingTimeInterval(autoStartLookahead) {
            return now >= Bedtime.bedtime(nextAlarmTarget: nextTarget, goalMinutes: goalMinutes)
        }
        return calendar.component(.hour, from: now) >= autoStartHour
    }

    /// Art zu einer Bezeichnung eines Klassifikators, unabhängig von Gross-/Kleinschreibung: SoundAnalysis
    /// (iPhone: «snoring», «speech», «whispering», «shout») und YAMNet (Android: «Snoring», «Speech»,
    /// «Whispering», «Conversation»). Alles andere: `other`.
    public static func typeOf(_ label: String) -> SoundType {
        switch label.trimmingCharacters(in: .whitespaces).lowercased() {
        case "snoring": .snore
        case "speech", "whispering", "conversation", "shout": .talk
        default: .other
        }
    }

    /// Art aus den Werten je Bezeichnung (z. B. Höchstwert über den Ausschnitt): die Bezeichnung mit dem
    /// höchsten Wert (bei Gleichstand die alphabetisch letzte, wie Kotlin `maxWith … thenBy key`).
    public static func classify(_ scores: [String: Double]) -> SoundType {
        let best = scores.filter { $0.value > 0 }.max { $0.value != $1.value ? $0.value < $1.value : $0.key < $1.key }
        return best.map { typeOf($0.key) } ?? .other
    }

    /// Pegel eines Blocks in dBFS (Effektivwert, Werte −1…1); Stille ergibt −140.
    public static func levelDb(_ samples: UnsafeBufferPointer<Float>) -> Double {
        guard !samples.isEmpty else { return silenceDb }
        var sum = 0.0
        for s in samples { sum += Double(s) * Double(s) }
        let rms = (sum / Double(samples.count)).squareRoot()
        return rms <= 1e-7 ? silenceDb : max(silenceDb, 20 * log10(rms))
    }

    /// Bilanz der Ausschnitte (einer Nacht).
    public static func summary(_ clips: [SoundClip]) -> SoundSummary {
        SoundSummary(
            snoreMs: clips.filter { $0.type == .snore }.reduce(Int64(0)) { $0 + $1.durationMs },
            snoreClips: clips.filter { $0.type == .snore }.count,
            talkClips: clips.filter { $0.type == .talk }.count,
            otherClips: clips.filter { $0.type == .other }.count
        )
    }

    /// Fügt einen Ausschnitt hinzu. Hat seine Nacht danach mehr als `max` Ausschnitte, fallen die leisesten
    /// weg (bei gleichem Pegel die jüngeren) – auch der neue selbst, wenn er der leiseste ist.
    public static func add(_ clips: [SoundClip], _ clip: SoundClip, max: Int = SoundEvents.maxClipsPerNight) -> (kept: [SoundClip], dropped: [SoundClip]) {
        let all = clips + [clip]
        let night = all.filter { $0.night == clip.night }
        guard night.count > max else { return (sorted(all), []) }
        let dropped = Array(night.sorted { $0.peakDb != $1.peakDb ? $0.peakDb < $1.peakDb : $0.at > $1.at }.prefix(night.count - max))
        return (sorted(all.filter { !dropped.contains($0) }), dropped)
    }

    /// Teilt in (behalten, abgelaufen): abgelaufen ist, was vor `now` − 7 Tagen (je 24 Std.) begann.
    public static func expire(_ clips: [SoundClip], now: Date, days: Int = SoundEvents.retentionDays) -> (kept: [SoundClip], expired: [SoundClip]) {
        let limit = now.addingTimeInterval(-TimeInterval(days) * 86_400)
        return (clips.filter { $0.at >= limit }, clips.filter { $0.at < limit })
    }

    /// Die Ausschnitte einer Nacht, älteste zuerst.
    public static func forNight(_ clips: [SoundClip], night: String) -> [SoundClip] {
        clips.filter { $0.night == night }.sorted { $0.at < $1.at }
    }

    /// Nachtdaten mit Ausschnitten, zu denen es keine aufgezeichnete Nacht gibt (z. B. Uhr nicht getragen):
    /// Morgenbericht und Verlauf zeigen ihre Geräusche in einer eigenen Karte. Neueste zuerst, je Datum mit
    /// dem Beginn des jüngsten Ausschnitts (zum Einordnen zwischen die Nächte). Identisch zu Kotlin.
    public static func nightsWithoutRecord(_ clips: [SoundClip], recorded: Set<String>) -> [(night: String, last: Date)] {
        var last: [String: Date] = [:]
        for clip in clips where !recorded.contains(clip.night) {
            last[clip.night] = Swift.max(last[clip.night] ?? clip.at, clip.at)
        }
        return last.map { (night: $0.key, last: $0.value) }
            .sorted { $0.last != $1.last ? $0.last > $1.last : $0.night > $1.night }
    }

    /// Nachtdatum eines Geräuschs (wie bei den Notizen).
    public static func nightDate(_ at: Date, calendar: Calendar) -> String { SleepNotes.nightDate(at, calendar: calendar) }

    public static func encode(_ clips: [SoundClip]) -> String {
        sorted(clips).map {
            [$0.night, String(millis($0.at)), $0.type.rawValue, String($0.durationMs), String($0.peakDb), $0.file].joined(separator: "|")
        }.joined(separator: "\n")
    }

    /// Liest `encode`; unlesbare Zeilen (und Dateinamen mit Pfad) werden übersprungen, unbekannte Arten zu `other`.
    public static func decode(_ text: String?) -> [SoundClip] {
        var seen = Set<String>()
        var out: [SoundClip] = []
        for line in (text ?? "").components(separatedBy: "\n") {
            let f = line.components(separatedBy: "|")
            guard f.count >= 6, isSafeName(f[5]), NightDate.isValid(f[0]),
                  let ms = Int64(f[1]), let duration = Int64(f[3]), let peak = Int(f[4]), seen.insert(f[5]).inserted else { continue }
            out.append(SoundClip(night: f[0], at: Date(timeIntervalSince1970: TimeInterval(ms) / 1000), type: SoundType(rawValue: f[2]) ?? .other,
                                 durationMs: Swift.max(0, duration), peakDb: peak, file: f[5]))
        }
        return sorted(out)
    }

    /// Ob ein Dateiname gültig ist (keine Pfade): Schutz beim Löschen und Abspielen.
    public static func isSafeFile(_ name: String) -> Bool { isSafeName(name) && name != "." && name != ".." }

    /// `[A-Za-z0-9._-]{1,80}` wie in Kotlin.
    private static func isSafeName(_ name: String) -> Bool {
        (1...80).contains(name.count) && name.unicodeScalars.allSatisfy {
            ("a"..."z").contains($0) || ("A"..."Z").contains($0) || ("0"..."9").contains($0) || $0 == "." || $0 == "_" || $0 == "-"
        }
    }

    /// Millisekunden seit 1970, abgerundet wie `Instant.toEpochMilli()`.
    static func millis(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded(.down)) }

    private static func sorted(_ clips: [SoundClip]) -> [SoundClip] {
        clips.sorted { $0.at != $1.at ? $0.at < $1.at : $0.file < $1.file }
    }
}

/// Findet Geräusche in einem Strom von Pegeln. Rein rechnerisch, ohne Audio: Der Aufrufer misst den Pegel
/// je Block von 100 ms und führt die Aktionen aus (Ausschnitt beginnen, beenden, speichern).
/// Identisch zu `SoundEventDetector` (Kotlin).
///
/// - Grundrauschen = Median der Sekundenpegel der letzten 60 s (Sekundenpegel = Mittel der Blockpegel
///   einer Sekunde); erst ab 10 s Vorlauf wird erkannt.
/// - Ein Geräusch beginnt, wenn der Pegel mindestens 500 ms am Stück über Grundrauschen + 12 dB liegt.
///   Beginn = erster dieser Blöcke.
/// - Laut ist ein Block nur, wenn er zudem mindestens −50 dBFS erreicht: In einem sehr stillen Zimmer zählen
///   leise Geräusche (Rascheln, Lüfter) nicht, auch wenn sie 12 dB über dem Grundrauschen liegen.
/// - Ausschnitt: 2 s vor dem Beginn bis höchstens 10 s insgesamt; früher zu Ende, wenn es 2 s lang leise ist.
/// - Das Geräusch dauert, bis es 30 s lang leise ist (Dauer = Beginn bis Ende des letzten lauten Blocks),
///   höchstens 10 Min. So gibt es höchstens einen Ausschnitt je 30 s, und langes Schnarchen zählt als ein
///   Geräusch mit seiner ganzen Dauer.
///
/// `atMs` = Beginn des Blocks in ms (beliebiger, gleichmässig steigender Bezug, z. B. ab Aufnahmebeginn).
public struct SoundEventDetector: Sendable {
    public enum Action: Equatable, Sendable {
        /// Ausschnitt beginnt bei `clipStartMs` (Vorlauf ab dort übernehmen); das Geräusch bei `onsetMs`.
        case clipStart(clipStartMs: Int64, onsetMs: Int64)
        /// Ausschnitt endet bei `clipEndMs` (exklusiv): speichern und einordnen.
        case clipEnd(onsetMs: Int64, clipEndMs: Int64)
        /// Geräusch zu Ende.
        case eventEnd(onsetMs: Int64, durationMs: Int64, peakDb: Double)
    }

    public static let frameMs: Int64 = 100
    public static let floorSeconds = 60
    public static let minFloorSeconds = 10
    public static let thresholdDb = 12.0
    /// Absolute Untergrenze für einen lauten Block in dBFS (leiser zählt nie als Geräusch).
    public static let minEventDb = -50.0
    public static let minLoudMs: Int64 = 500
    public static let preRollMs: Int64 = 2_000
    public static let maxClipMs: Int64 = 10_000
    public static let clipTailMs: Int64 = 2_000
    public static let eventGapMs: Int64 = 30_000
    public static let maxEventMs: Int64 = 10 * 60_000

    private var seconds: [Double] = []
    private var secondSum = 0.0
    private var secondFrames = 0
    private var floor: Double?

    private var loudRun = 0
    private var runStartMs: Int64 = 0
    private var runPeak = -Double.infinity

    // Laufendes Geräusch.
    private var onsetMs: Int64?
    private var lastLoudEndMs: Int64 = 0
    private var peak = -Double.infinity
    private var clipStartMs: Int64?

    public init() {}

    /// Aktuelle Schwelle in dBFS; `nil` während des Vorlaufs.
    public var threshold: Double? { floor.map { $0 + Self.thresholdDb } }

    public mutating func onFrame(atMs: Int64, levelDb: Double) -> [Action] {
        var actions: [Action] = []
        let loud = (threshold.map { levelDb > $0 } ?? false) && levelDb >= Self.minEventDb
        let frameEnd = atMs + Self.frameMs

        if let onset = onsetMs {
            if loud {
                lastLoudEndMs = frameEnd
                peak = max(peak, levelDb)
            }
            if let clip = clipStartMs, frameEnd - clip >= Self.maxClipMs || frameEnd - lastLoudEndMs >= Self.clipTailMs {
                actions.append(.clipEnd(onsetMs: onset, clipEndMs: min(frameEnd, clip + Self.maxClipMs)))
                clipStartMs = nil
            }
            if frameEnd - lastLoudEndMs >= Self.eventGapMs || frameEnd - onset >= Self.maxEventMs {
                actions += endEvent()
            }
        } else if loud {
            if loudRun == 0 {
                runStartMs = atMs
                runPeak = -Double.infinity
            }
            loudRun += 1
            runPeak = max(runPeak, levelDb)
            if Int64(loudRun) * Self.frameMs >= Self.minLoudMs {
                onsetMs = runStartMs
                lastLoudEndMs = frameEnd
                peak = runPeak
                let start = runStartMs - Self.preRollMs
                clipStartMs = start
                loudRun = 0
                actions.append(.clipStart(clipStartMs: start, onsetMs: runStartMs))
            }
        } else {
            loudRun = 0
        }

        addToFloor(levelDb)
        return actions
    }

    /// Aufnahme endet: offenen Ausschnitt und offenes Geräusch abschliessen.
    public mutating func finish(atMs: Int64) -> [Action] {
        guard let onset = onsetMs else { return [] }
        var actions: [Action] = []
        if let clip = clipStartMs {
            actions.append(.clipEnd(onsetMs: onset, clipEndMs: max(onset, min(atMs, clip + Self.maxClipMs))))
        }
        clipStartMs = nil
        actions += endEvent()
        return actions
    }

    private mutating func endEvent() -> [Action] {
        guard let onset = onsetMs else { return [] }
        var result: [Action] = []
        if let clip = clipStartMs {
            result.append(.clipEnd(onsetMs: onset, clipEndMs: min(lastLoudEndMs, clip + Self.maxClipMs)))
        }
        result.append(.eventEnd(onsetMs: onset, durationMs: max(0, lastLoudEndMs - onset), peakDb: peak))
        onsetMs = nil
        clipStartMs = nil
        peak = -Double.infinity
        loudRun = 0
        return result
    }

    private mutating func addToFloor(_ levelDb: Double) {
        secondSum += levelDb
        secondFrames += 1
        if Int64(secondFrames) * Self.frameMs < 1000 { return }
        seconds.append(secondSum / Double(secondFrames))
        secondSum = 0
        secondFrames = 0
        while seconds.count > Self.floorSeconds { seconds.removeFirst() }
        floor = seconds.count >= Self.minFloorSeconds ? Self.median(seconds) : nil
    }

    public static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        guard !sorted.isEmpty else { return 0 }
        let mid = sorted.count / 2
        return sorted.count % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
    }
}
