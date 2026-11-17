import Foundation
import PhaseWakeCore

/// Neuplanen der Sicherungen: gewünschte Nächte bestimmen und mit den gestellten abgleichen (`apply`).
extension BackupAlarm {
    private static let oneShotKey = "backupOneShotTargets"

    /// Bestimmt die gewünschten Sicherungen und gleicht sie mit den gestellten ab (`apply`), statt alles zu löschen:
    /// Eine Sicherung, deren Zielzeit vorbei ist, deren Nachfrist aber noch läuft, bleibt (`AlarmSchedule.inGrace`) –
    /// startet die App genau dann neu, ist das iPhone womöglich der einzige Wecker. Ebenso eine Sicherung, deren
    /// Weckfenster vor einem Zeitzonenwechsel schon begonnen hat (`AlarmSchedule.begunNight` in der Zone ihrer
    /// Planung): Sie klingelt zur geplanten absoluten Zeit (07:00 Zürich = 15:00 Tokio), nicht erst zur neuen Ortszeit.
    func rescheduleNow(_ alarms: [AlarmSpec]) async {
        let now = Date()
        let grace = currentGrace
        let planned = plannedBackups
        let holidays = host.holidayDates
        let suppressed = suppressedUntil()
        let eligible = alarms.filter(\.phoneBackup)
        // Begonnene Nächte nach einer Reise zuerst: Ein einmaliger Wecker behält damit sein erstes Vorkommen
        // (`oneShotTargets`). Ohne verschobene Ortszeit seit der Planung (`WatchRules.zoneShifted`) bleibt alles wie
        // bisher.
        let begun = begunNights(planned, eligible: eligible, now: now, holidays: holidays, suppressed: suppressed)
        let firstTargets = oneShotTargets(alarms, now: now, kept: begun.mapValues(\.slot.target))
        // Einmaliger Wecker, dessen erstes Vorkommen samt Nachfrist vorbei ist: ausschalten (geht auch an die Uhr) –
        // aber erst, wenn seine Sicherung geklingelt hat.
        let expired = alarms.filter { spec in
            guard let first = firstTargets[spec.id], now > first.addingTimeInterval(grace) else { return false }
            return !planned.contains { $0.slot.alarmId == spec.id && $0.slot.fireAt > now }
        }
        for spec in expired { host.disableAfterRing(spec) }
        let skip = Set(expired.map(\.id))
        let held = heldSlots(planned, eligible: eligible, skip: skip, begun: begun, now: now, holidays: holidays,
                             suppressed: suppressed)
        let kept = held.kept
        // Je Wecker gesucht (`BackupPlan.nights`): Zwei Wecker mit derselben Zielzeit bekommen beide eine Sicherung.
        // Eine behaltene Nacht nie doppelt, auch falls die Suche dieselbe Zielzeit nochmals liefert.
        // «Gute Nacht» auf der Uhr: diese Nacht zur früheren Zielzeit (`WatchNight`, wie `OnsetShift` auf Android).
        let input = BackupPlan.Input(eligible, suppressed: suppressed, firstTargets: firstTargets, skip: skip,
                                     holidays: holidays)
        let nightPlans = BackupPlan.nights(input, now: now, until: now.addingTimeInterval(Self.horizon),
                                           maxNights: Self.maxSlots, calendar: .gregorianLocal)
            .compactMap { host.shifted($0, now: now) }
        var wanted = held.wanted
        var nights = 0
        for plan in nightPlans {
            let sound = alarms.first { $0.id == plan.alarmId }?.sound ?? ""
            let slot = BackupSlot(alarmId: plan.alarmId, target: plan.target,
                                  fireAt: plan.target.addingTimeInterval(grace), sound: sound,
                                  zone: TimeZone.current.identifier)
            guard !kept.contains(where: {
                $0.slot.alarmId == slot.alarmId && abs($0.slot.target.timeIntervalSince(slot.target)) < 0.001
            }) else { continue }
            wanted.append(slot)
            nights += 1
        }
        await apply(wanted, now: now)
        for entry in kept {
            await pinNotification(entry)
            // Diagnose einmal je Nacht, nicht bei jedem Neuplanen.
            guard keptNight != entry.slot.target else { continue }
            keptNight = entry.slot.target
            DiagStore.shared.log(.scheduled, "zone change, keep night \(Format.logTime(entry.slot.target))")
        }
        DiagStore.shared.log(.scheduled,
                             "phone backup \(nights) nights, \(wanted.count - nights) in grace, grace \(Int(grace)) s")
    }

    /// Je Wecker die gestellte Sicherung, deren Weckfenster vor einem Zeitzonenwechsel schon begonnen hat.
    private func begunNights(_ planned: [PlannedBackup], eligible: [AlarmSpec], now: Date, holidays: Set<String>,
                             suppressed: [String: Date]) -> [String: PlannedBackup] {
        var begun: [String: PlannedBackup] = [:]
        for entry in planned where entry.slot.fireAt > now {
            guard WatchRules.zoneShifted(plannedZone: entry.slot.zone, target: entry.slot.target, zone: .current),
                  let spec = eligible.first(where: { $0.id == entry.slot.alarmId }),
                  AlarmSchedule.begunNight(spec, plannedTarget: entry.slot.target,
                                           plannedCalendar: entry.slot.plannedCalendar,
                                           now: now, grace: entry.slot.fireAt.timeIntervalSince(entry.slot.target),
                                           holidays: holidays,
                                           suppressedUntil: suppressed[spec.id]) != nil else { continue }
            begun[spec.id] = entry
        }
        return begun
    }

    /// Gestellte Sicherungen, die bleiben: in der Nachfrist (`wanted`) oder begonnene Nacht nach einer Reise
    /// (`wanted` und `kept`).
    private func heldSlots(_ planned: [PlannedBackup], eligible: [AlarmSpec], skip: Set<String>,
                           begun: [String: PlannedBackup], now: Date, holidays: Set<String>,
                           suppressed: [String: Date]) -> (wanted: [BackupSlot], kept: [PlannedBackup]) {
        var wanted: [BackupSlot] = []
        var kept: [PlannedBackup] = []
        for entry in planned where entry.slot.fireAt > now {
            guard let spec = eligible.first(where: { $0.id == entry.slot.alarmId }),
                  !skip.contains(spec.id) else { continue }
            if AlarmSchedule.inGrace(spec, plannedTarget: entry.slot.target, now: now,
                                     grace: entry.slot.fireAt.timeIntervalSince(entry.slot.target),
                                     calendar: .gregorianLocal,
                                     holidays: holidays, suppressedUntil: suppressed[spec.id]) != nil {
                wanted.append(entry.slot)
            } else if begun[spec.id]?.id == entry.id {
                wanted.append(entry.slot)
                kept.append(entry)
            }
        }
        return (wanted, kept)
    }

    /// Zielzeit des ersten Vorkommens je eingeschaltetem einmaligem Wecker, gemerkt ab dem ersten Planen.
    /// Neu berechnet, wenn sich die Uhrzeit ändert; ausgeschaltete Wecker fallen heraus. Der Schlüssel enthält keine
    /// Zeitzone: Ist die gemerkte Zielzeit schon da, bleibt sie (fester Zeitpunkt) – nach einer Reise klingelt der
    /// Wecker sonst am nächsten Tag nochmals. Liegt sie noch vorn, folgt sie der Ortszeit wie auf der Uhr, ausser ihr
    /// Weckfenster hat schon begonnen (`kept`, je Wecker die Zielzeit der behaltenen Nacht): Dann bleibt diese.
    private func oneShotTargets(_ alarms: [AlarmSpec], now: Date, kept: [String: Date]) -> [String: Date] {
        let stored = settings.dictionary(forKey: Self.oneShotKey) as? [String: Double] ?? [:]
        var keep: [String: Double] = [:]
        var targets: [String: Date] = [:]
        for spec in alarms where spec.isOneShot && spec.enabled {
            let key = "\(spec.id)|\(spec.hour):\(spec.minute)"
            // Früher mit Zeitzone gemerkt («…|Europe/Zurich»): weiter gelten lassen.
            let raw = stored[key] ?? stored.first { $0.key.hasPrefix(key + "|") }?.value
            let reached = raw.map(Date.init(timeIntervalSince1970:)).flatMap { $0 <= now ? $0 : nil }
            guard let target = reached ?? kept[spec.id]
                    ?? AlarmSchedule.nextTarget(spec, now: now, calendar: .gregorianLocal) else { continue }
            keep[key] = target.timeIntervalSince1970
            targets[spec.id] = target
        }
        settings.set(keep, forKey: Self.oneShotKey)
        return targets
    }
}
