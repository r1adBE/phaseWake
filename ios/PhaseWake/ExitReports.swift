import Foundation
import MetricKit
import PhaseWakeCore

/// Abstürze, Hänger und Ausnahmen (Rechenzeit, Schreiben) der iPhone-App aus MetricKit, zusammengefasst ins
/// Diagnose-Protokoll (wie die Prozessenden auf Android): je Ereignis eine Zeile mit Art, Zeit,
/// Typ und Codes, ohne Stacks. Nichts verlässt das Gerät. Die Apple Watch hat kein MetricKit.
final class ExitReports: NSObject, MXMetricManagerSubscriber, @unchecked Sendable {
    static let shared = ExitReports()

    /// Ende (s seit 1970) des neuesten schon protokollierten Berichts in `UserDefaults.standard`: keiner doppelt.
    private static let seenKey = "exitReportsSeenUntil"

    /// Meldet sich bei MetricKit an (beim Start der App); Berichte kommen danach auf einer beliebigen Warteschlange.
    func start() {
        MXMetricManager.shared.add(self)
    }

    func didReceive(_ payloads: [MXDiagnosticPayload]) {
        let seen = UserDefaults.standard.double(forKey: Self.seenKey)
        var newest = seen
        for payload in payloads {
            let end = payload.timeStampEnd.timeIntervalSince1970
            guard ExitLines.isNew(end: end, seenUntil: seen) else { continue }
            newest = max(newest, end)
            for line in Self.lines(payload) { DiagStore.shared.log(.error, line) }
        }
        if newest > seen { UserDefaults.standard.set(newest, forKey: Self.seenKey) }
    }

    /// Zeilen eines Berichts, je Art höchstens ``ExitLines/maxPerKind``.
    private static func lines(_ payload: MXDiagnosticPayload) -> [String] {
        let at = payload.timeStampEnd
        let limit = ExitLines.maxPerKind
        var out: [String] = []
        for d in (payload.crashDiagnostics ?? []).prefix(limit) {
            out.append(ExitLines.line("crash", at: at, fields: [
                ("sig", ExitLines.number(d.signal)), ("exc", ExitLines.number(d.exceptionType)),
                ("code", ExitLines.number(d.exceptionCode)), ("v", d.applicationVersion), ("reason", d.terminationReason),
            ]))
        }
        for d in (payload.hangDiagnostics ?? []).prefix(limit) {
            out.append(ExitLines.line("hang", at: at, fields: [("dur", ExitLines.seconds(d.hangDuration)), ("v", d.applicationVersion)]))
        }
        for d in (payload.cpuExceptionDiagnostics ?? []).prefix(limit) {
            out.append(ExitLines.line("cpu", at: at, fields: [
                ("cpu", ExitLines.seconds(d.totalCPUTime)), ("sampled", ExitLines.seconds(d.totalSampledTime)), ("v", d.applicationVersion),
            ]))
        }
        for d in (payload.diskWriteExceptionDiagnostics ?? []).prefix(limit) {
            out.append(ExitLines.line("disk", at: at, fields: [("writes", ExitLines.megabytes(d.totalWritesCaused)), ("v", d.applicationVersion)]))
        }
        return out
    }
}

/// Text der Zeilen von ``ExitReports``: «exit crash at 2026-11-17 03:12 sig=11 exc=1 code=0 v=1.4 reason=…».
enum ExitLines {
    /// Höchstens so viele Zeilen je Art und Bericht (ein Bericht flutet das Protokoll nicht).
    static let maxPerKind = 5
    /// Längster Grund des Systems (`terminationReason`) in Zeichen.
    static let maxReason = 60

    /// Eine Zeile; Felder ohne Wert fehlen, der Grund wird auf ``maxReason`` Zeichen gekürzt.
    static func line(_ kind: String, at: Date, fields: [(String, String?)], zone: TimeZone = .current) -> String {
        var parts = ["exit", kind, "at", stamp(at, zone: zone)]
        for (key, value) in fields {
            guard let text = value?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { continue }
            parts.append("\(key)=\(key == "reason" ? String(text.prefix(maxReason)) : text)")
        }
        return parts.joined(separator: " ")
    }

    /// Ob ein Bericht mit diesem Ende (s seit 1970) noch nicht protokolliert ist.
    static func isNew(end: TimeInterval, seenUntil: TimeInterval) -> Bool {
        end > seenUntil
    }

    /// Ganze Zahl als Text; `nil` ohne Wert.
    static func number(_ value: NSNumber?) -> String? {
        value?.stringValue
    }

    /// Dauer in Sekunden mit einer Stelle («2.5s»).
    static func seconds(_ value: Measurement<UnitDuration>) -> String {
        String(format: "%.1fs", locale: Locale(identifier: "en_US_POSIX"), value.converted(to: .seconds).value)
    }

    /// Datenmenge in ganzen MB («1100MB»).
    static func megabytes(_ value: Measurement<UnitInformationStorage>) -> String {
        String(format: "%.0fMB", locale: Locale(identifier: "en_US_POSIX"), value.converted(to: .megabytes).value)
    }

    private static func stamp(_ date: Date, zone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = zone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
}
