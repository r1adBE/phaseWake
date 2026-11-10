import CoreMotion
import Foundation
import PhaseWakeCore
import WatchKit

/// «Nacht aufzeichnen» auf der Apple Watch – Gegenstück zu `RecordControl`/`RecordService` der Galaxy Watch, gleiche Texte
/// und Regeln: Start auf der Uhr oder vom iPhone, Ende von Hand (Uhr oder iPhone), nach 12 Std. oder unter 15 % Akku
/// (``NightRecording/endNow(start:now:battery:)``). Gemessen wird mit dem Bewegungs-Rekorder von watchOS über die ganze
/// Zeit (50 Hz, auch bei geschlossener App); den Puls je Epoche liefert danach Apple Health. Die fertige Aufzeichnung geht
/// wie eine der Lernphase ans iPhone (``NightRecording``, dort die letzten 14, im Verlauf und im Export, zählt für die
/// Lernphase).
///
/// Grenzen von watchOS: Den Rekorder kann die App nicht anhalten (er läuft bis zu seinem geplanten Ende weiter, seine
/// Werte danach werden nicht gelesen), und den Akku prüft sie nur, wenn sie läuft (bei jedem Durchgang, die
/// Hintergrund-Aktualisierung ist auf höchstens 30 Min gestellt; watchOS kann sie verzögern).
extension LearnRecorder {
    /// Warum «Nacht aufzeichnen» nicht startet.
    enum Refusal: Equatable {
        /// Akku unter ``NightRecording/minBattery``.
        case battery
        /// Keine Erlaubnis für Bewegung (oder kein Rekorder auf dieser Uhr).
        case motion
    }

    private static let recorderFromKey = "recorderFrom"
    private static let recorderUntilKey = "recorderUntil"

    // MARK: Rekorder

    /// Zeitraum, für den der Rekorder zuletzt gestartet wurde; `nil`, wenn keiner bekannt ist.
    var recorderSpan: (from: Date, until: Date)? {
        let from = SettingsFile.watchSession.double(forKey: Self.recorderFromKey)
        let until = SettingsFile.watchSession.double(forKey: Self.recorderUntilKey)
        guard from > 0, until > from else { return nil }
        return (from: Date(timeIntervalSince1970: from), until: Date(timeIntervalSince1970: until))
    }

    /// Ob der Rekorder laut dem zuletzt gestarteten Zeitraum gerade aufzeichnet.
    var recorderRunning: Bool { recorderSpan.map { Date() < $0.until } ?? false }

    /// Ob der Rekorder jetzt starten darf: vorhanden und erlaubt; noch nicht gefragt nur bei offener App (dann fragt
    /// watchOS beim Start).
    static var recorderAllowed: Bool {
        guard CMSensorRecorder.isAccelerometerRecordingAvailable() else { return false }
        switch CMSensorRecorder.authorizationStatus() {
        case .authorized: return true
        case .notDetermined: return WKApplication.shared().applicationState == .active
        default: return false
        }
    }

    /// Startet den Rekorder bis `until` (höchstens 12 Std. ab jetzt). Läuft er schon mindestens so lange, kein zweiter
    /// Aufruf: Apple dokumentiert nicht, was ein zweiter Aufruf während einer Aufzeichnung tut (er könnte sie verkürzen).
    /// Reicht er weniger weit, wird er verlängert; der Beginn bleibt dann der der laufenden Aufzeichnung.
    func ensureRecorder(until: Date) {
        let now = Date()
        let end = min(until, now.addingTimeInterval(NightRecording.maxDuration))
        let span = recorderSpan
        if let span, span.until > now, span.until >= end { return }
        guard end > now else { return }
        CMSensorRecorder().recordAccelerometer(forDuration: end.timeIntervalSince(now))
        let from = span.flatMap { $0.until > now ? $0.from : nil } ?? now
        SettingsFile.watchSession.set(from.timeIntervalSince1970, forKey: Self.recorderFromKey)
        SettingsFile.watchSession.set(end.timeIntervalSince1970, forKey: Self.recorderUntilKey)
    }

    // MARK: Starten und stoppen

    /// «Nacht aufzeichnen» von der Uhr (`by` «watch») oder vom iPhone («phone»). Läuft schon eine Aufzeichnung (auch die
    /// der Lernphase), bleibt sie (wie auf Android). - Returns: der Grund, wenn nichts startet.
    @discardableResult
    func startManual(by: String) async -> Refusal? {
        if since != nil { return nil }
        let battery = Self.battery()
        if let battery, battery < NightRecording.minBattery {
            DiagStore.shared.log(.battery, "record not started (\(by)): battery \(battery)%")
            return .battery
        }
        guard Self.recorderAllowed else {
            DiagStore.shared.log(.measureStart, "record not started (\(by)): motion not allowed")
            return .motion
        }
        if let pending = mark {
            if Date() < Self.end(of: pending) {
                // Lernphase für später in der Nacht (ihre Auswertung hat noch nicht begonnen): Die Aufzeichnung von Hand
                // ersetzt sie und zählt (wie auf Android, «recording runs»).
                save(nil)
            } else {
                // Eine frühere Aufzeichnung ist noch nicht fertig ausgewertet: so weit wie möglich, dann ans iPhone.
                await process(pending, budget: Self.foregroundBudget)
                if let rest = mark { await finish(rest) }
            }
        }
        // Inzwischen anders gestartet (zweiter Tipp, Befehl vom iPhone): nichts doppelt.
        if since != nil { return nil }
        let now = Date()
        let until = now.addingTimeInterval(NightRecording.maxDuration)
        ensureRecorder(until: until)
        save(Mark(alarmId: "", target: until, from: now, until: until, wokeAt: nil, motions: [], manual: true))
        DiagStore.shared.log(.measureStart, "record start by \(by) bat=\(battery.map { "\($0)" } ?? "-") mode=recorder")
        scheduleRefresh()
        return nil
    }

    /// Stoppt die laufende Aufzeichnung (auch die der Lernphase): von Hand mit ``RecordEnd/watch`` bzw.
    /// ``RecordEnd/phone``, bei tiefem Akku ``RecordEnd/battery``. Ausgewertet wird danach bis jetzt.
    func stop(_ reason: RecordEnd, battery: Int? = nil) {
        guard var current = mark, since != nil else { return }
        current.stoppedAt = Date()
        current.stopReason = reason.rawValue
        save(current)
        DiagStore.shared.log(reason == .battery ? .battery : .measureEnd, "record stop \(Self.reasonText(reason, battery: battery))")
        plan()
    }

    /// Akku-Wache wie auf der Galaxy Watch: Unter 15 % endet eine laufende Aufzeichnung (der Wecker hat Vorrang).
    func checkBattery() {
        guard let current = mark, since != nil, let battery = Self.battery(),
              NightRecording.endNow(start: current.from, now: Date(), battery: battery) == .battery else { return }
        stop(.battery, battery: battery)
    }

    /// Grund fürs Diagnose-Protokoll wie `RecordService.reasonText` (Kotlin): «manual watch», «manual phone», «cap 12h»,
    /// «battery 14%», «service ended», «wake».
    static func reasonText(_ reason: RecordEnd, battery: Int?) -> String {
        switch reason {
        case .watch: "manual watch"
        case .phone: "manual phone"
        case .cap: "cap \(Int(NightRecording.maxDuration / 3600))h"
        case .battery: "battery \(battery.map { "\($0)" } ?? "-")%"
        case .service: "service ended"
        case .wake: "wake"
        }
    }

    // MARK: Einschlafzeit und Anzeige

    /// Einschlafzeit aus dem Rekorder (`WatchOnset`) in die laufende bzw. noch nicht ausgewertete Aufzeichnung, wenn sie in
    /// deren Zeitraum fällt (wie `appendOnset` auf Android, auch bei einer von Hand begonnenen).
    func noteOnset(bedAt: Date, asleepAt: Date) {
        guard var current = mark, current.from <= asleepAt, asleepAt < Self.end(of: current) else { return }
        current.bedAt = bedAt
        current.asleepAt = asleepAt
        save(current)
    }

    /// Laufende Aufzeichnung an die Startseite und ans iPhone («Aufzeichnung läuft seit …»).
    func publishSince() {
        publishedSince = since
        WatchModel.shared.noteRecording(since: since)
        WatchConnectivityBridge.shared.publish(recordSince: since)
    }
}
