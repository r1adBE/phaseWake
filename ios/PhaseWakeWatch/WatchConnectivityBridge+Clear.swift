import Foundation
import PhaseWakeCore
import WatchConnectivity

extension WatchConnectivityBridge {
    /// «Verlauf löschen» bis `clearedAt`: Nächte und Aufzeichnungen, die bis dahin endeten und noch nicht beim iPhone
    /// sind (vor der Aktivierung gemerkt oder noch in der Warteschlange von WatchConnectivity), nicht mehr senden.
    @MainActor
    func dropQueued(clearedAt: Date) {
        func cleared(_ payload: [String: Any]) -> Bool {
            if let text = payload[SyncKeys.night] as? String, let night = try? Codec.decodeNight(text) {
                return !HistoryClear.keeps(end: night.wokeAt, clearedAt: clearedAt)
            }
            if let text = payload[SyncKeys.learnRecording] as? String, let recording = NightRecording.decode(text) {
                return !HistoryClear.keeps(end: recording.until, clearedAt: clearedAt)
            }
            return false
        }
        let before = pending.count
        pending.removeAll { cleared($0.payload) }
        var cancelled = before - pending.count
        if WCSession.isSupported(), WCSession.default.activationState == .activated {
            for transfer in WCSession.default.outstandingUserInfoTransfers where cleared(transfer.userInfo) {
                transfer.cancel()
                cancelled += 1
            }
        }
        if cancelled > 0 { DiagStore.shared.log(.sync, "history cleared, \(cancelled) queued dropped") }
    }
}
