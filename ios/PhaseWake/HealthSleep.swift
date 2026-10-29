import Foundation
import HealthKit
import PhaseWakeCore

/// Schlafsitzungen aus Apple Health mit den Namen ihrer Quellen (nur im Speicher, nie gespeichert).
struct HealthNights: Sendable {
    /// Sitzungen, je Quelle zusammengefasst, in beliebiger Reihenfolge.
    var sessions: [HealthSession] = []
    /// Anzeigename je `HealthSession.source`; fehlt für die Quellen von Apple (Apple Watch, iPhone).
    var names: [String: String] = [:]
}

/// Liest die Schlafphasen, die Apple Health nach dem Aufwachen ablegt. Nur Lesen, nur auf Wunsch.
final class HealthSleep: @unchecked Sendable {
    private let store = HKHealthStore()
    private let type = HKCategoryType(.sleepAnalysis)

    /// Quelle für alles, was Apple selbst schreibt (Apple Watch, iPhone): Apple vergibt je Gerät eine eigene
    /// Bundle-ID «com.apple.health.<UUID>»; zusammengefasst ergeben «Im Bett» vom iPhone und die Phasen der Uhr
    /// eine Sitzung, die im Bett beginnt und die Phasen trägt.
    static let appleSource = "com.apple.health"

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    func requestAccess() async -> Bool {
        guard isAvailable else { return false }
        do {
            try await store.requestAuthorization(toShare: [], read: [type])
            return true
        } catch {
            return false
        }
    }

    /// Schlafphasen rund um die Nacht; leer ohne Daten oder Zugriff.
    func stages(for night: NightRecord) async -> [StageSpan] {
        let samples = await query(from: night.windowStart.addingTimeInterval(-14 * 3600), to: night.wokeAt.addingTimeInterval(2 * 3600))
        return samples.compactMap(Self.span).sorted { $0.start < $1.start }
    }

    /// Schlafsitzungen der letzten `HealthHistory.days` Tage (einen Tag mehr, damit die älteste Nacht ganz drin ist);
    /// leer ohne Daten oder Zugriff. Ohne Zugriff liefert HealthKit einfach nichts (Leserechte bleiben verborgen).
    func sessions(now: Date = Date()) async -> HealthNights {
        guard isAvailable else { return HealthNights() }
        let samples = await query(from: now.addingTimeInterval(-Double(HealthHistory.days + 1) * 86_400), to: now)
        var bySource: [String: [HKCategorySample]] = [:]
        var names: [String: String] = [:]
        for sample in samples {
            let source = sample.sourceRevision.source
            let key = source.bundleIdentifier.hasPrefix("com.apple.") ? Self.appleSource : source.bundleIdentifier
            bySource[key, default: []].append(sample)
            if key != Self.appleSource { names[key] = source.name }
        }
        return HealthNights(sessions: bySource.flatMap { Self.sessions($0.value, source: $0.key) }, names: names)
    }

    /// Alle Proben im Zeitraum in einer Abfrage.
    private func query(from: Date, to: Date) async -> [HKCategorySample] {
        guard isAvailable else { return [] }
        let predicate = HKQuery.predicateForSamples(withStart: from, end: to)
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: nil) { _, results, _ in
                continuation.resume(returning: (results as? [HKCategorySample]) ?? [])
            }
            store.execute(query)
        }
    }

    /// Proben einer Quelle zu Sitzungen: Lücken unter `SleepSummary.newNightGap` (3 h) gehören zur selben Sitzung.
    /// Beginn und Ende zählen auch «Im Bett» mit; Phasen sind alle anderen Proben. Hat eine Sitzung bekannte Phasen,
    /// fallen «Schlaf (ohne Phase)»-Proben weg, die sich damit überschneiden – ältere Geräte schreiben beides, und
    /// doppelt gezählt wäre der Schlaf zu lang.
    private static func sessions(_ samples: [HKCategorySample], source: String) -> [HealthSession] {
        var groups: [[HKCategorySample]] = []
        for sample in samples.sorted(by: { $0.startDate < $1.startDate }) {
            if let group = groups.last, let end = group.map(\.endDate).max(), sample.startDate.timeIntervalSince(end) < SleepSummary.newNightGap {
                groups[groups.count - 1].append(sample)
            } else {
                groups.append([sample])
            }
        }
        return groups.compactMap { group in
            guard let start = group.map(\.startDate).min(), let end = group.map(\.endDate).max() else { return nil }
            let spans = group.compactMap(Self.span)
            let known = spans.filter { $0.stage != .unknown }
            let kept = spans.filter { s in s.stage != .unknown || !known.contains { $0.start < s.end && $0.end > s.start } }
            return HealthSession(start: start, end: end, stages: kept.sorted { $0.start < $1.start }, source: source)
        }
    }

    private static func span(_ sample: HKCategorySample) -> StageSpan? {
        stage(sample.value).map { StageSpan(start: sample.startDate, end: sample.endDate, stage: $0) }
    }

    private static func stage(_ value: Int) -> SleepStage? {
        switch HKCategoryValueSleepAnalysis(rawValue: value) {
        case .asleepCore: .light
        case .asleepDeep: .deep
        case .asleepREM: .rem
        case .awake: .awake
        case .asleepUnspecified: .unknown
        default: nil // «Im Bett» ist keine Schlafphase
        }
    }
}
