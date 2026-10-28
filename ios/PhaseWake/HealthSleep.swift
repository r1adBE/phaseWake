import Foundation
import HealthKit
import PhaseWakeCore

/// Liest die Schlafphasen, die Apple Health nach dem Aufwachen ablegt. Nur Lesen, nur auf Wunsch.
final class HealthSleep: @unchecked Sendable {
    private let store = HKHealthStore()
    private let type = HKCategoryType(.sleepAnalysis)

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
        guard isAvailable else { return [] }
        let predicate = HKQuery.predicateForSamples(
            withStart: night.windowStart.addingTimeInterval(-14 * 3600),
            end: night.wokeAt.addingTimeInterval(2 * 3600)
        )
        let samples: [HKCategorySample] = await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: nil) { _, results, _ in
                continuation.resume(returning: (results as? [HKCategorySample]) ?? [])
            }
            store.execute(query)
        }
        return samples.compactMap { sample in
            guard let stage = Self.stage(sample.value) else { return nil }
            return StageSpan(start: sample.startDate, end: sample.endDate, stage: stage)
        }.sorted { $0.start < $1.start }
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
