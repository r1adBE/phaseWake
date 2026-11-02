import Foundation
import PhaseWakeCore
import UserNotifications
#if canImport(AlarmKit)
import AlarmKit
#endif

/// Zweitwecker auf dem iPhone.
///
/// Ab iOS 26 über AlarmKit: echter Wecker, der auch bei Lautlos und Fokus klingelt. Er klingelt,
/// wenn die Uhr den Weckmoment meldet, und als Sicherung nach der Nachfrist (`grace`: 2 Minuten mit Uhr, ohne Uhr
/// pünktlich), falls sich die Uhr nicht meldet. Vor iOS 26 bleibt nur eine zeitkritische Mitteilung (weniger
/// zuverlässig, der Stummschalter macht sie lautlos). Stoppen oder Schlummern am iPhone nimmt die Uhr mit (wie
/// Android), sofern sie das Klingeln ausgelöst hat; eine Sicherung schlummert auf dem iPhone selbst.
/// Einmalige Wecker: Sicherung nur für ihr erstes Vorkommen; danach schaltet das iPhone sie selbst aus.
@MainActor
final class BackupAlarm {
    /// Pro Wecker: Sicherung ausgesetzt bis (Sekunden seit 1970).
    private static let suppressedKey = "backupSuppressedByAlarm"
    private static let legacySuppressedKey = "backupSuppressedUntil"
    private static let ringingKey = "backupRingingId"
    private static let ringSnoozesKey = "backupRingSnoozesLeft"
    private static let oneShotKey = "backupOneShotTargets"
    private static let snoozeSafetyKey = "backupSnoozeSafetyId"
    private static let snoozedAtKey = "backupSnoozedAt"
    /// Schlummern an einer Sicherung: der nächste Alarm in 5 Minuten und sein Weckton.
    private static let backupSnoozeKey = "backupSnoozeId"
    private static let backupSnoozeToneKey = "backupSnoozeTone"
    /// Sicherung des laufenden Probeweckers oder Powernaps.
    private static let transientKey = "backupTransientId"
    /// Das «stop» einer älteren Uhr direkt nach dem Schlummern ist nur das Echo davon, kein Beenden.
    private static let snoozeEcho: TimeInterval = 30
    /// Klingelt die Uhr nach dem Schlummern nicht wieder (Sitzung nicht gestartet): dann das iPhone
    /// (wie `RingMessage.SNOOZE_SAFETY_MS` auf Android).
    private static let snoozeSafetyDelay: TimeInterval = 90
    /// So weit im Voraus steht die Sicherung – auch wenn die App tagelang nicht geöffnet wird (14 Tage).
    static let horizon: TimeInterval = 14 * 24 * 3600
    /// Höchstens so viele Sicherungen: Vor iOS 26 sind es Mitteilungen, und iOS behält nur 64 wartende je App
    /// (dazu kommen Bettzeit-Erinnerungen, Wochenrückblick, Schlummern und Probewecker).
    private static let maxSlots = 40
    /// Gespeichert statt nur im Speicher: Startet iOS die App neu, kann «stop» der Uhr ihn trotzdem stoppen.
    private var ringingId: UUID? {
        get { storedId(Self.ringingKey) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: Self.ringingKey) }
    }
    /// Neuplanungen laufen nacheinander, nie verschränkt (sonst blieben Alarme ohne gemerkte Kennung).
    private var queue: Task<Void, Never>?
    /// Von der App selbst gestoppte oder verworfene Alarme (Befehl der Uhr, Neuplanen): Ihr Stopp-Intent schickt nichts
    /// an die Uhr (`backupStopped`). Nur im Speicher, wie der Intent im selben Prozess läuft.
    var stoppedByApp: Set<UUID> = []

    /// Plant die Sicherung für die nächsten Nächte mit Zweitwecker.
    func reschedule(_ alarms: [AlarmSpec]) async {
        let previous = queue
        let task = Task { @MainActor in
            await previous?.value
            await self.rescheduleNow(alarms)
        }
        queue = task
        await task.value
    }

    /// Wartet, bis die laufende Neuplanung fertig ist (Nacht-Check liest danach die gestellten Sicherungen).
    func settled() async { await queue?.value }

    /// Bestimmt die gewünschten Sicherungen und gleicht sie mit den gestellten ab (`apply`), statt alles zu löschen:
    /// Eine Sicherung, deren Zielzeit vorbei ist, deren Nachfrist aber noch läuft, bleibt (`AlarmSchedule.inGrace`) –
    /// startet die App genau dann neu, ist das iPhone womöglich der einzige Wecker.
    private func rescheduleNow(_ alarms: [AlarmSpec]) async {
        let now = Date()
        let grace = Self.grace
        let planned = plannedBackups
        let holidays = PhoneModel.shared.holidays.dates
        let suppressed = suppressedUntil()
        let firstTargets = oneShotTargets(alarms, now: now)
        // Einmaliger Wecker, dessen erstes Vorkommen samt Nachfrist vorbei ist: ausschalten (geht auch an die Uhr) –
        // aber erst, wenn seine Sicherung geklingelt hat.
        let expired = alarms.filter { spec in
            guard let first = firstTargets[spec.id], now > first.addingTimeInterval(grace) else { return false }
            return !planned.contains { $0.slot.alarmId == spec.id && $0.slot.fireAt > now }
        }
        for spec in expired { PhoneModel.shared.setEnabled(spec, false) }
        let skip = Set(expired.map(\.id))
        let eligible = alarms.filter(\.phoneBackup)
        var wanted: [BackupSlot] = []
        for entry in planned where entry.slot.fireAt > now {
            guard let spec = eligible.first(where: { $0.id == entry.slot.alarmId }), !skip.contains(spec.id),
                  AlarmSchedule.inGrace(spec, plannedTarget: entry.slot.target, now: now,
                                        grace: entry.slot.fireAt.timeIntervalSince(entry.slot.target), calendar: .gregorianLocal,
                                        holidays: holidays, suppressedUntil: suppressed[spec.id]) != nil else { continue }
            wanted.append(entry.slot)
        }
        // Je Wecker gesucht (`BackupPlan.nights`): Zwei Wecker mit derselben Zielzeit bekommen beide eine Sicherung.
        let nightPlans = BackupPlan.nights(eligible, now: now, until: now.addingTimeInterval(Self.horizon), maxNights: Self.maxSlots,
                                           suppressed: suppressed, firstTargets: firstTargets, skip: skip,
                                           calendar: .gregorianLocal, holidays: holidays)
        let nights = nightPlans.count
        for plan in nightPlans {
            let sound = alarms.first { $0.id == plan.alarmId }?.sound ?? ""
            wanted.append(BackupSlot(alarmId: plan.alarmId, target: plan.target, fireAt: plan.target.addingTimeInterval(grace), sound: sound))
        }
        await apply(wanted, now: now)
        DiagStore.shared.log(.scheduled, "phone backup \(nights) nights, \(wanted.count - nights) in grace, grace \(Int(grace)) s")
    }

    /// Zielzeit des ersten Vorkommens je eingeschaltetem einmaligem Wecker, gemerkt ab dem ersten Planen.
    /// Neu berechnet, wenn sich die Uhrzeit ändert; ausgeschaltete Wecker fallen heraus. Der Schlüssel enthält keine
    /// Zeitzone: Ist die gemerkte Zielzeit schon da, bleibt sie (fester Zeitpunkt) – nach einer Reise klingelt der
    /// Wecker sonst am nächsten Tag nochmals. Liegt sie noch vorn, folgt sie der Ortszeit wie auf der Uhr.
    private func oneShotTargets(_ alarms: [AlarmSpec], now: Date) -> [String: Date] {
        let stored = UserDefaults.standard.dictionary(forKey: Self.oneShotKey) as? [String: Double] ?? [:]
        var keep: [String: Double] = [:]
        var targets: [String: Date] = [:]
        for spec in alarms where spec.isOneShot && spec.enabled {
            let key = "\(spec.id)|\(spec.hour):\(spec.minute)"
            // Früher mit Zeitzone gemerkt («…|Europe/Zurich»): weiter gelten lassen.
            let raw = stored[key] ?? stored.first { $0.key.hasPrefix(key + "|") }?.value
            let reached = raw.map(Date.init(timeIntervalSince1970:)).flatMap { $0 <= now ? $0 : nil }
            guard let target = reached ?? AlarmSchedule.nextTarget(spec, now: now, calendar: .gregorianLocal) else { continue }
            keep[key] = target.timeIntervalSince1970
            targets[spec.id] = target
        }
        UserDefaults.standard.set(keep, forKey: Self.oneShotKey)
        return targets
    }

    /// Die Uhr klingelt gerade: sofort mitklingeln (falls gewünscht) und die Sicherung dieser Nacht aussetzen.
    /// `alarmId` und `target` bestimmen die Nacht laut Uhr (ältere Versionen schicken die Zielzeit nicht).
    /// - Returns: Kennung des Mitklingel-Alarms, oder `nil`, wenn das iPhone nicht mitklingelt (Weckart «aus»).
    @discardableResult
    func ringNow(alarmId: String?, alarm: AlarmSpec?, target: Date?, snoozesLeft: Int) async -> UUID? {
        // Die Uhr klingelt wieder: Die Sicherung fürs Schlummern ist nicht mehr nötig.
        await cancelSnoozeSafety()
        UserDefaults.standard.set(snoozesLeft, forKey: Self.ringSnoozesKey)
        var ring: UUID?
        if alarm?.phone != .off {
            let id = UUID()
            ringingId = id
            setNight(RingNight(alarmId: alarmId, target: target), for: id)
            ring = id
            await schedule(id: id, at: Date().addingTimeInterval(2), fromWatch: true, snoozesLeft: snoozesLeft,
                           tone: alarm.flatMap { WakeTone(rawValue: $0.sound) })
        }
        await watchHandled(alarmId: alarmId, target: target)
        return ring
    }

    /// Die Uhr hat diese Nacht übernommen (klingelt, gestoppt oder Nacht gemeldet): Sicherung bis kurz
    /// nach der Zielzeit dieser Nacht aussetzen – nicht länger, sonst fiele ein zweiter Wecker weg.
    func watchHandled(alarmId: String?, target: Date?) async {
        let now = Date()
        let alarms = PhoneModel.shared.alarms
        let spec = alarms.first { $0.id == alarmId }
        let window = TimeInterval(AlarmSpec.maxWindowMinutes * 60)
        let nightTarget = target
            ?? spec.flatMap { AlarmSchedule.nextTarget($0, now: now.addingTimeInterval(-window), calendar: .gregorianLocal,
                                                    holidays: PhoneModel.shared.holidays.dates) }
            ?? now.addingTimeInterval(window)
        let until = nightTarget.addingTimeInterval(Self.grace)
        // Ohne Kennung (ältere Uhr): alle Wecker dieser Nacht, wie bisher.
        let ids = alarmId.map { [$0] } ?? alarms.map(\.id)
        var raw = UserDefaults.standard.dictionary(forKey: Self.suppressedKey) as? [String: Double] ?? [:]
        for id in ids { raw[id] = max(raw[id] ?? 0, until.timeIntervalSince1970) }
        let keepAfter = now.addingTimeInterval(-24 * 3600).timeIntervalSince1970
        UserDefaults.standard.set(raw.filter { $0.value > keepAfter }, forKey: Self.suppressedKey)
        UserDefaults.standard.removeObject(forKey: Self.legacySuppressedKey)
        await reschedule(alarms)
    }

    /// Pro Wecker: Sicherung ausgesetzt bis (die Uhr hat die Nacht übernommen); auch Teil von `PhoneModel.doneUntil`.
    func suppressedUntil() -> [String: Date] {
        let raw = UserDefaults.standard.dictionary(forKey: Self.suppressedKey) as? [String: Double] ?? [:]
        return raw.mapValues { Date(timeIntervalSince1970: $0) }
    }

    /// Stoppt, was klingelt. `night` = «stop» der Uhr: nur Alarme dieser Nacht (der Mitklingel-Alarm auch, wenn seine
    /// Nacht unbekannt ist); ein «stop» zu einer anderen Nacht lässt das iPhone weiterklingeln. `nil` = Stopp am iPhone:
    /// der Mitklingel-Alarm und jeder gerade klingelnde Alarm. `snoozed`: Die Uhr schlummert nur (Schlummer-Ende
    /// bekannt), ihre Sicherung bleibt.
    func stopRinging(night: RingNight? = nil, snoozed: Bool = false) async {
        let id = ringingId
        let ownsRing = id.map { ring in night.map { stop in self.night(for: ring).map(stop.same) ?? true } ?? true } ?? false
        if ownsRing { ringingId = nil }
        // Beendet (nicht nur das Echo des eigenen Schlummerns): auch die Schlummer-Sicherungen dieser Nacht weg.
        let snoozedAt = UserDefaults.standard.double(forKey: Self.snoozedAtKey)
        if !snoozed, Date().timeIntervalSince1970 - snoozedAt > Self.snoozeEcho {
            if belongs(storedId(Self.snoozeSafetyKey), to: night, unknown: true) { await cancelSnoozeSafety() }
            if belongs(storedId(Self.backupSnoozeKey), to: night, unknown: true) { cancelBackupSnooze() }
        }
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            let alerting = ((try? AlarmManager.shared.alarms) ?? []).filter { alarm in
                alarm.state == .alerting && (alarm.id == id ? ownsRing : belongs(alarm.id, to: night, unknown: false))
            }
            // Vor dem Stoppen: Der Probewecker zählt nur, was wirklich geklingelt hat.
            for alarm in alerting { PhoneModel.shared.transient.phoneAlerted(alarm.id.uuidString) }
            if ownsRing, let id {
                try? AlarmManager.shared.stop(id: id)
                // Noch nicht klingelnd (geplant in 2 s): verwerfen.
                try? AlarmManager.shared.cancel(id: id)
            }
            for alarm in alerting {
                stoppedByApp.insert(alarm.id)
                try? AlarmManager.shared.stop(id: alarm.id)
            }
            return
        }
        #endif
        guard ownsRing, let id else { return }
        let delivered = await UNUserNotificationCenter.current().deliveredNotifications().map(\.request.identifier)
        if delivered.contains(id.uuidString) { PhoneModel.shared.transient.phoneAlerted(id.uuidString) }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id.uuidString])
    }

    /// Ob der iPhone-Alarm `id` zur Nacht `night` gehört; `night == nil` (Stopp am iPhone) trifft alles. Ist die Nacht
    /// des Alarms nicht gemerkt, gilt `unknown`.
    private func belongs(_ id: UUID?, to night: RingNight?, unknown: Bool) -> Bool {
        guard let id else { return false }
        guard let night else { return true }
        return self.night(for: id).map(night.same) ?? unknown
    }

    /// Ob das iPhone gerade mit der Uhr klingelt oder nach dem Schlummern auf sie wartet (Probewecker/Powernap sperren).
    var ringActive: Bool { ringingId != nil || storedId(Self.snoozeSafetyKey) != nil }

    /// Ob die Kennung zum gerade mitklingelnden Wecker gehört (nicht zu einem schon per Code gestoppten).
    func isCurrentRing(_ alarmId: String) -> Bool { ringingId?.uuidString == alarmId }

    // MARK: Schlummern

    /// Nacht des gerade mitklingelnden Alarms (vor dem Stoppen lesen); `nil`, wenn keiner klingelt oder unbekannt.
    var currentRingNight: RingNight? { ringingId.flatMap { self.night(for: $0) } }

    /// Am iPhone geschlummert: Eigene Sicherung kurz nach dem Schlummer-Ende, falls die Uhr dann nicht
    /// klingelt (ihre neue Sitzung startet im Hintergrund unter Umständen nicht). `night` = `currentRingNight`.
    func snoozeSafety(night: RingNight?) async {
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.snoozedAtKey)
        await scheduleSnoozeSafety(at: Snooze.nextRing(Date()).addingTimeInterval(Self.snoozeSafetyDelay), night: night)
    }

    /// Die Uhr hat geschlummert (ihr «stop» trägt das Schlummer-Ende `until`): Sicherung 90 s nach dem Schlummer-Ende,
    /// wie `RingMessage.snoozeSafetyAt` auf Android. Liegt der Zeitpunkt schon zurück (verspätet zugestellt, die Uhr
    /// hat inzwischen wieder geklingelt), keine.
    func watchSnoozed(night: RingNight, until: Date) async {
        let at = until.addingTimeInterval(Self.snoozeSafetyDelay)
        guard at > Date() else { return }
        await scheduleSnoozeSafety(at: at, night: night)
        DiagStore.shared.log(.snooze, "watch, phone safety \(Format.time(at))")
    }

    private func scheduleSnoozeSafety(at date: Date, night: RingNight?) async {
        await cancelSnoozeSafety()
        let id = UUID()
        UserDefaults.standard.set(id.uuidString, forKey: Self.snoozeSafetyKey)
        setNight(night, for: id)
        // Runden wie auf der Uhr: Diese Runde ist verbraucht.
        let left = max(0, UserDefaults.standard.integer(forKey: Self.ringSnoozesKey) - 1)
        await schedule(id: id, at: date, snoozesLeft: left)
    }

    private func cancelSnoozeSafety() async {
        guard let id = storedId(Self.snoozeSafetyKey) else { return }
        UserDefaults.standard.removeObject(forKey: Self.snoozeSafetyKey)
        cancelAlarm(id)
    }

    /// «Schlummern» an einer Sicherung (klingelt ohne Uhr, nur ab iOS 26): Alarm `alarmId` stoppen und in 5 Minuten
    /// wieder klingeln, mit `snoozesLeft` − 1 Runden; gehört zur selben Nacht wie der geschlummerte Alarm.
    func backupSnoozed(_ alarmId: String, snoozesLeft: Int) async {
        guard let id = UUID(uuidString: alarmId) else { return }
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *) {
            stoppedByApp.insert(id)
            try? AlarmManager.shared.stop(id: id)
        }
        #endif
        let tone = plannedBackups.first { $0.id == id }?.slot.sound
            ?? (storedId(Self.backupSnoozeKey) == id ? UserDefaults.standard.string(forKey: Self.backupSnoozeToneKey) : nil)
        let snoozedNight = self.night(for: id)
        cancelBackupSnooze()
        let next = UUID()
        UserDefaults.standard.set(next.uuidString, forKey: Self.backupSnoozeKey)
        UserDefaults.standard.set(tone, forKey: Self.backupSnoozeToneKey)
        setNight(snoozedNight, for: next)
        await schedule(id: next, at: Snooze.nextRing(Date()), snoozesLeft: max(0, snoozesLeft - 1), tone: tone.flatMap(WakeTone.init(rawValue:)))
        DiagStore.shared.log(.snooze, "phone backup, \(max(0, snoozesLeft - 1)) left")
    }

    /// «Stopp» an einer Sicherung, die ohne Uhr klingelt (nur ab iOS 26): Die Uhr hört für dieselbe Nacht auch auf (sie
    /// klingelt etwa über ihren Rückfall oder schlummert noch). Hat die App den Alarm selbst gestoppt, nichts senden;
    /// ohne gemerkte Nacht auch nicht (sonst träfe es jede Nacht der Uhr).
    func backupStopped(_ alarmId: String) {
        guard let id = UUID(uuidString: alarmId), stoppedByApp.remove(id) == nil, let night = night(for: id) else { return }
        DiagStore.shared.log(.stop, "phone backup, watch too")
        PhoneConnectivity.shared.send(command: SyncKeys.stop, night: night)
    }

    private func cancelBackupSnooze() {
        guard let id = storedId(Self.backupSnoozeKey) else { return }
        UserDefaults.standard.removeObject(forKey: Self.backupSnoozeKey)
        cancelAlarm(id)
    }

    // MARK: Probewecker und Powernap

    /// Sicherung für einen Probewecker oder Powernap: klingelt kurz nach dessen Zielzeit, falls sich die Uhr
    /// nicht meldet (wie die Sicherung einer Nacht). Ohne iPhone-Wecker («aus») keine.
    /// - Returns: Kennung der Sicherung, oder `nil` ohne Sicherung.
    @discardableResult
    func scheduleTransient(_ transient: TransientPlan) async -> UUID? {
        await cancelTransient()
        guard transient.spec.phoneBackup else { return nil }
        let id = UUID()
        UserDefaults.standard.set(id.uuidString, forKey: Self.transientKey)
        setNight(RingNight(alarmId: transient.plan.alarmId, target: transient.plan.target), for: id)
        let snoozes = Transient.allowsSnooze(transient.plan.alarmId) ? Snooze.maxCount : 0
        await schedule(id: id, at: transient.plan.target.addingTimeInterval(Self.grace), snoozesLeft: snoozes,
                       tone: WakeTone(rawValue: transient.spec.sound))
        DiagStore.shared.log(.scheduled, "phone backup \(transient.kind.rawValue) \(Format.time(transient.plan.target))")
        return id
    }

    /// Sicherung des Probeweckers bzw. Powernaps verwerfen (die Uhr hat geklingelt, gestoppt oder abgebrochen).
    func cancelTransient() async {
        guard let id = storedId(Self.transientKey) else { return }
        UserDefaults.standard.removeObject(forKey: Self.transientKey)
        cancelAlarm(id)
    }

    // MARK: Gemerkte Kennungen

    /// Kennungen der einzeln gemerkten Alarme (Mitklingeln, Schlummer-Sicherung, Schlummern einer Sicherung,
    /// Probewecker) samt gestellter Sicherungen; nur deren Nächte bleiben gemerkt.
    var activeIds: [UUID] {
        [Self.ringingKey, Self.snoozeSafetyKey, Self.backupSnoozeKey, Self.transientKey].compactMap(storedId)
            + plannedBackups.map(\.id)
    }

    private func storedId(_ key: String) -> UUID? {
        UserDefaults.standard.string(forKey: key).flatMap(UUID.init(uuidString:))
    }
}
