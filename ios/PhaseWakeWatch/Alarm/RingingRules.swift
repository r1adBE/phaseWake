import Foundation
import PhaseWakeCore
import WatchKit

/// Entscheide beim Klingeln, Schlummern, Beenden und beim Rückfall der Apple Watch ohne Sitzung, Mitteilungen und
/// Vibration (testbar). Gegenstück zu `WakeRules` und `RingFlow` (Kotlin).
enum RingingRules {
    // MARK: Klingeln

    /// Verbleibende Schlummer-Runden nach `snoozes` Runden für den Wecker `alarmId`; der Probewecker prüft genau einen
    /// Weckvorgang und schlummert nie (wie `WakeRules.snoozesLeft`, Kotlin).
    static func snoozesLeft(alarmId: String?, snoozes: Int) -> Int {
        Transient.allowsSnooze(alarmId) ? Snooze.maxCount - snoozes : 0
    }

    /// Ob Klingeln oder Schlummern über die Zeitgrenze `limit` nach der Zielzeit `target` hinaus dauert
    /// (hängengeblieben, z. B. nach einem Neustart der App ohne Sitzung). Ohne Nacht immer.
    static func ringExpired(target: Date?, now: Date, limit: TimeInterval) -> Bool {
        guard let target else { return true }
        return now > target.addingTimeInterval(limit)
    }

    /// Ob «stop» bzw. «schlummern» vom iPhone (Wecker `alarmId`, Zielzeit `target`) zu der Nacht gehört, die hier
    /// klingelt oder schlummert (`ownAlarmId`, `ownTarget`): gleiche Kennung und Zielzeit auf 1 s genau (Millisekunden
    /// werden unterwegs gekürzt). Fehlende Angaben (ältere iPhone-Version, keine Nacht) widersprechen nicht.
    static func isThisNight(alarmId: String?, target: Date?, ownAlarmId: String?, ownTarget: Date?) -> Bool {
        if let alarmId, let ownAlarmId, alarmId != ownAlarmId { return false }
        if let target, let ownTarget, abs(target.timeIntervalSince(ownTarget)) >= 1 { return false }
        return true
    }

    // MARK: Schlummern

    /// Was «schlummern» bewirkt (auf der Uhr oder vom iPhone).
    enum SnoozeChoice: Equatable {
        /// Es klingelt nicht: nichts.
        case ignore
        /// Noch Runden: schlummern.
        case snooze
        /// Keine Runde mehr: weiterklingeln (vom iPhone: «klingeln» zurück, damit auch das iPhone weiterklingelt).
        case keepRinging
    }

    /// Entscheid für «schlummern», während es klingelt (`ringing`) und `snoozesLeft` Runden bleiben.
    static func snoozeChoice(ringing: Bool, snoozesLeft: Int) -> SnoozeChoice {
        guard ringing else { return .ignore }
        return snoozesLeft > 0 ? .snooze : .keepRinging
    }

    /// Eine Schlummer-Runde ab `now`.
    struct SnoozeRound: Equatable {
        /// Schlummer-Ende: Start der neuen Sitzung.
        let until: Date
        /// Plan der neuen Sitzung (Fenster und Zielzeit am Schlummer-Ende).
        let plan: WakePlan
        /// Rückfall, falls die neue Sitzung nicht startet.
        let fallbackAt: Date
    }

    /// Schlummer-Runde für den Wecker `alarmId` ab `now` (`Snooze.nextRing`), Rückfall `delay` nach ihrem Ende.
    static func snoozeRound(alarmId: String, now: Date, delay: TimeInterval) -> SnoozeRound {
        let until = Snooze.nextRing(now)
        return SnoozeRound(until: until, plan: WakePlan(alarmId: alarmId, windowStart: until, target: until),
                           fallbackAt: until.addingTimeInterval(delay))
    }

    // MARK: Beenden

    /// Was ein Stopp beendet.
    enum Dismissal: Equatable {
        /// Es klingelt und schlummert nichts: Nach einer abgebrochenen Sitzung ist die Nacht schon abgeschlossen, nur
        /// die Kette `interruptedId` endet.
        case chainOnly
        /// Die Nacht endet ganz; `clearsUpcoming`: auch schon zugestellte Rückfälle späterer Nächte entfernen (nicht
        /// bei Probewecker und Powernap).
        case night(clearsUpcoming: Bool)
    }

    /// Entscheid für einen Stopp (Uhr, iPhone oder Weckhinweis des Systems) im Zustand klingeln (`ringing`) bzw.
    /// schlummern (`snoozed`) der Nacht `alarmId`.
    static func dismissal(ringing: Bool, snoozed: Bool, alarmId: String?) -> Dismissal {
        guard ringing || snoozed else { return .chainOnly }
        return .night(clearsUpcoming: Transient.kindOf(alarmId) == nil)
    }

    /// Wann weitergeweckt wird, wenn die Sitzung beim Klingeln oder Schlummern endet (`SmartAlarm.finishInterrupted`):
    /// nach der Zeitgrenze (`expired`) nicht mehr (`nil`); beim Schlummern bis `snoozedUntil` zu dessen Ende plus
    /// `delay`; sonst jetzt.
    static func interruptedRingWake(expired: Bool, snoozedUntil: Date?, now: Date, delay: TimeInterval) -> Date? {
        guard !expired else { return nil }
        if let snoozedUntil, snoozedUntil > now { return snoozedUntil.addingTimeInterval(delay) }
        return now
    }

    /// Was der Abschluss einer Nacht (`SmartAlarm.finish`) festhält.
    struct Finish: Equatable {
        /// Probewecker oder Powernap: kein Verlauf, kein «erledigt», kein Ausschalten.
        let transient: Bool
        /// Nacht in den Verlauf, als erledigt und ans iPhone: geklingelt und kein vorübergehender Plan.
        let records: Bool
    }

    /// Abschluss der Nacht `night`, zuerst geklingelt um `firstRing` (`nil`: nie geklingelt).
    static func finish(night: WakePlan?, firstRing: Date?) -> Finish {
        let transient = Transient.kindOf(night?.alarmId) != nil
        return Finish(transient: transient, records: night != nil && firstRing != nil && !transient)
    }

    /// Ob der Akku der Nacht verworfen wird: nie geklingelt (`firstRing`), also keine Nacht im Verlauf, und kein
    /// vorübergehender Plan (`transient`, der keinen begonnen hat).
    static func dropsBattery(firstRing: Date?, transient: Bool) -> Bool {
        firstRing == nil && !transient
    }

    /// Ob der Wecker `alarm` nach dem Klingeln ausgeschaltet wird: einmalig, noch eingeschaltet, kein vorübergehender
    /// Plan (`transient`).
    static func disablesAfterRing(_ alarm: AlarmSpec, transient: Bool) -> Bool {
        !transient && alarm.isOneShot && alarm.enabled
    }

    /// Ob «Gute Nacht» (Wecker `goodNightAlarmId`, Zielzeit `goodNightTarget`) die Nacht `night` vorverlegt hat:
    /// gleicher Wecker, gleiche Zielzeit auf die Millisekunde. Ohne Nacht oder ohne «Gute Nacht» nicht.
    static func shiftedByGoodNight(_ night: WakePlan?, goodNightAlarmId: String?, goodNightTarget: Date?) -> Bool {
        guard let night, let goodNightAlarmId, let goodNightTarget else { return false }
        return goodNightAlarmId == night.alarmId
            && TimeBounds.millis(goodNightTarget) == TimeBounds.millis(night.target)
    }

    // MARK: Rückfall

    /// Kennung, unter der ein angekommener Rückfall `notificationId` gilt: der einer späteren Nacht
    /// (`UpcomingFallbacks`) wie der des echten Weckers, sonst die Kennung seiner Kette (`NotificationChain`).
    static func arrivedId(_ notificationId: String) -> String {
        UpcomingFallbacks.isUpcoming(notificationId) ? SmartAlarm.fallbackId : NotificationChain.baseId(notificationId)
    }

    /// Ob der Rückfall `id` die geplante oder wartende Nacht `night` (Rückfall-Kennung `key`) jetzt weckt: Zielzeit da,
    /// aber höchstens `limit` zurück (ein alter Rückfall, spät angetippt, weckt nicht mehr).
    static func fallbackDue(_ night: WakePlan, key: String, id: String, now: Date, limit: TimeInterval) -> Bool {
        key == id && night.target <= now && now < night.target.addingTimeInterval(limit)
    }

    /// Ob der Rückfall `id` das Schlummern bis `until` (Rückfall-Kennung der Nacht `key`) beendet: Die
    /// Schlummer-Sitzung hat bis jetzt nicht geweckt.
    static func endsSnooze(until: Date, key: String, id: String, now: Date) -> Bool {
        key == id && until <= now
    }

    /// Vibration der offenen App, nachdem ein Rückfall angekommen ist.
    enum FallbackHaptic: Equatable {
        /// Klingelt die Sitzung (`notifyUser` vibriert): nichts.
        case silent
        /// Klingelt nichts (Rückfall einer anderen Nacht, abgebrochene Sitzung): einmal.
        case once
        /// Klingelt ohne laufende Sitzung: selbst vibrieren, wiederholt, solange es klingelt.
        case repeating
    }

    /// Vibration nach einem Rückfall, wenn es klingelt (`ringing`) und die Sitzung im Zustand `session` ist (nur beim
    /// Klingeln gefragt).
    static func fallbackHaptic(ringing: Bool,
                               session: @autoclosure () -> WKExtendedRuntimeSessionState?) -> FallbackHaptic {
        guard ringing else { return .once }
        return session() == .running ? .silent : .repeating
    }

    /// Mitteilungen des Rückfalls `id` ab `date`: Rückfall des echten Weckers (``SmartAlarm/fallbackId``) und Wecken
    /// nach abgebrochener Sitzung (``SmartAlarm/interruptedId``) als Kette (`NotificationChain`), Probewecker und
    /// Powernap mit einer. - Returns: Kennungen und Zeitpunkte, gleich lang.
    @MainActor
    static func fallbackChain(_ id: String, at date: Date) -> (ids: [String], dates: [Date]) {
        let chained = id == SmartAlarm.fallbackId || id == SmartAlarm.interruptedId
        return chained ? (NotificationChain.ids(id), NotificationChain.dates(date)) : ([id], [date])
    }
}
