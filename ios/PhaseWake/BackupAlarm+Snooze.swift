import Foundation
import PhaseWakeCore
import SwiftUI

/// Schlummern einer Sicherung, die ohne Uhr klingelt (AlarmKit, «Schlummern» stellt einen neuen Alarm in 5 Minuten):
/// AlarmKit zeigt dazwischen nichts an. Die App zeigt es darum selbst und lässt es beenden, wie Android-Handy und beide
/// Uhren mit «Halten zum Stoppen» (Prüfrunde 2, B-9).
extension BackupAlarm {
    /// Ende des Schlummerns (dann klingelt die Sicherung wieder), solange es bevorsteht; sonst `nil`.
    func backupSnoozedUntil(now: Date = Date()) -> Date? {
        Self.snoozedUntil(id: storedId(Self.backupSnoozeKey), until: SettingsFile.phone.double(forKey: Self.backupSnoozeUntilKey), now: now)
    }

    /// Schlummert die Sicherung `id` bis `until` (Sekunden seit 1970, 0 = unbekannt) um `now`: das Ende, sonst `nil`.
    static func snoozedUntil(id: UUID?, until: Double, now: Date) -> Date? {
        guard id != nil, until > 0, until.isFinite else { return nil }
        let end = Date(timeIntervalSince1970: until)
        return end > now ? end : nil
    }

    /// «Halten zum Stoppen» in der App: Das Schlummern endet, die Uhr hört für dieselbe Nacht auch auf (wie «Stopp» an
    /// der Sicherung, `backupStopped`).
    func stopBackupSnooze() {
        guard let id = storedId(Self.backupSnoozeKey) else { return }
        let night = self.night(for: id)
        cancelBackupSnooze()
        DiagStore.shared.log(.stop, "phone backup snooze, in app")
        if let night { PhoneConnectivity.shared.send(command: SyncKeys.stop, night: night) }
    }
}

/// Karte auf der Startseite, solange eine Sicherung schlummert: «Schlummern bis 06:55» und «Halten zum Stoppen» (1,2 s
/// halten, wie auf den Uhren; für VoiceOver eine Aktion).
struct BackupSnoozeCard: View {
    @Environment(PhoneModel.self) private var model
    let now: Date
    @State private var progress = 0.0

    var body: some View {
        // Neu zeichnen, sobald sich das Schlummern ändert (`BackupAlarm` meldet es über die Einstellungen).
        let _ = PrivateSettingChanges.shared.revision
        if let until = model.backup.backupSnoozedUntil(now: now) {
            VStack(alignment: .leading, spacing: 10) {
                Label(L("ring_snoozed_until", Format.time(until)), systemImage: "zzz")
                    .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Text(L("ring_hold_to_stop"))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .body))
                    .foregroundStyle(Brand.nightBottom)
                    .frame(maxWidth: .infinity, minHeight: 48)
                    .background {
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Brand.wave
                                Brand.moon.frame(width: geo.size.width * progress)
                            }
                        }
                    }
                    .clipShape(Capsule())
                    .contentShape(Capsule())
                    .onLongPressGesture(minimumDuration: 1.2, perform: stop) { pressing in
                        withAnimation(pressing ? .linear(duration: 1.2) : .easeOut(duration: 0.25)) { progress = pressing ? 1 : 0 }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L("ring_hold_to_stop"))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction(.default, stop)
            }
            .card()
        }
    }

    private func stop() {
        progress = 0
        model.backup.stopBackupSnooze()
    }
}
