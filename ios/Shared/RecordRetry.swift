import Foundation
import PhaseWakeCore

/// «Nacht aufzeichnen» nachreichen (Vollaudit AP2-10): Antwortet die Uhr nicht auf den Start (App dort zu), zeigt das
/// iPhone den Hinweis, die App auf der Uhr zu öffnen. Wird die Uhr danach innerhalb von ``window`` nach dem Tippen
/// erreichbar, schickt das iPhone den Start nochmals, mit der Zeit des Tippens (``SyncKeys/retryTapAt``). Die Uhr nimmt
/// einen Start sonst nur frisch an (höchstens ``SyncKeys/maxRingAgeMs`` alt), einen nachgereichten bis ``window`` nach
/// dem Tippen. Später beginnt keine Aufzeichnung mehr, die niemand mehr erwartet. Rein, ohne Plattform (testbar).
enum RecordRetry {
    /// So lange nach dem Tippen gilt ein nachgereichter Start.
    static let window: TimeInterval = 10 * 60

    /// iPhone: ob der Start, getippt um `tapAt`, beim Erreichbarwerden der Uhr um `now` nochmals gesendet wird – nur,
    /// solange er unbeantwortet ist (`unanswered`, Hinweis sichtbar) und höchstens ``window`` zurückliegt.
    static func resend(unanswered: Bool, tapAt: Date?, now: Date) -> Bool {
        guard unanswered, let tapAt else { return false }
        let age = now.timeIntervalSince(tapAt)
        return age >= 0 && age <= window
    }

    /// Uhr: ob ein Start mit Sendezeit `sentAtMs` (und, nachgereicht, Zeit des Tippens `retryTapMs`) um `now` gilt:
    /// frisch wie bisher, oder nachgereicht mit einem Tippen höchstens ``window`` zurück. Unplausible Zeiten gelten nicht.
    static func accepts(sentAtMs: Int64?, retryTapMs: Int64?, now: Date) -> Bool {
        if let age = SyncTime.ageMs(sentAtMs, now: now), age <= SyncKeys.maxRingAgeMs { return true }
        guard retryTapMs != nil, SyncTime.ageMs(sentAtMs, now: now) != nil,
              let tapAge = SyncTime.ageMs(retryTapMs, now: now) else { return false }
        return tapAge >= -SyncKeys.maxRingAgeMs && tapAge <= Int64(window * 1000)
    }
}
