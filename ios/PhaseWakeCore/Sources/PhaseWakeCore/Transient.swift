import Foundation

/// Art eines vorübergehenden Plans. Rohwerte wie die Kotlin-Namen.
public enum TransientKind: String, CaseIterable, Sendable {
    /// Probewecker «In 2 Minuten testen».
    case test = "TEST"
    /// Powernap: Zielzeit nach der gewählten Dauer.
    case nap = "NAP"
}

/// Ein einmaliger Plan, der kein gespeicherter Wecker ist: Probewecker oder Powernap. Die Uhr führt ihn wie
/// eine Nacht (Messen, Klingeln, Stopp), aber ohne Eintrag im Verlauf und ohne Einfluss auf die Wecker.
/// Identisch zu `TransientPlan` (Kotlin).
public struct TransientPlan: Equatable, Sendable {
    /// Probewecker oder Powernap.
    public let kind: TransientKind
    /// Weckart (iPhone, Uhr-Ton, Weckton, Sonnenaufgang); Kennung mit `Transient.testPrefix` bzw. `Transient.napPrefix`.
    public let spec: AlarmSpec
    /// Fenster und Zielzeit; gilt statt des Fensters von `spec`.
    public let plan: WakePlan

    /// Erzeugt einen Plan; `plan.alarmId` ist `spec.id`.
    public init(kind: TransientKind, spec: AlarmSpec, plan: WakePlan) {
        self.kind = kind
        self.spec = spec
        self.plan = plan
    }
}

/// Warum ein Probewecker oder Powernap jetzt nicht starten darf (`Transient.conflict`). Rohwerte wie die Kotlin-Namen.
public enum TransientConflict: String, CaseIterable, Sendable {
    /// Ein Weckfenster läuft, oder eine Nacht klingelt bzw. schlummert: «Gerade läuft dein Wecker.»
    case alarmRunning = "ALARM_RUNNING"
    /// Das Weckfenster des nächsten Weckers beginnt vor der Zielzeit des Plans: «Dein Weckfenster beginnt schon vorher.»
    case alarmSoon = "ALARM_SOON"
}

/// Regeln und Format für `TransientPlan`. Auf iPhone, Apple Watch, Android und Wear OS gleich.
public enum Transient {
    /// Präfix der Kennung eines Probeweckers; eine UUID beginnt nie mit «t», darum eindeutig.
    public static let testPrefix = "test-"
    /// Präfix der Kennung eines Powernaps; eine UUID beginnt nie mit «n».
    public static let napPrefix = "nap-"
    /// Probewecker: Das Fenster beginnt so viele Sekunden nach dem Start.
    public static let testWindowStartSeconds: TimeInterval = 60
    /// Probewecker: Zielzeit so viele Sekunden nach dem Start («In 2 Minuten testen»).
    public static let testTargetSeconds: TimeInterval = 120
    /// Kürzester Powernap in Minuten.
    public static let napMinMinutes = 10
    /// Längster Powernap in Minuten.
    public static let napMaxMinutes = 90
    /// Vorschlag für die Dauer in Minuten.
    public static let napDefaultMinutes = 20
    /// Schritt der Dauerwahl in Minuten.
    public static let napStepMinutes = 5
    /// Weckfenster eines Powernaps: die letzten so vielen Minuten.
    public static let napWindowMinutes = 5
    /// Weckfenster kurzer Powernaps (bis `napShortLimitMinutes`): die letzten so vielen Minuten.
    public static let napShortWindowMinutes = 3
    /// Bis zu dieser Dauer (Minuten) gilt das kurze Fenster.
    public static let napShortLimitMinutes = 15
    /// So lange nach der Zielzeit gilt ein Plan noch (Klingeln, Schlummern, Stopp), in Sekunden; danach wird
    /// er verworfen. Gleich der Höchstdauer einer Nacht auf der Uhr (2 Stunden).
    public static let activeAfterTarget: TimeInterval = 2 * 3600

    /// So lange nach der Zielzeit (Sekunden) wird ein Plan noch geplant, auch nach einem Neustart: 60 s wie die
    /// Rückfall-Frist der Apple Watch (`SmartAlarm.fallbackDelay`). Ein Plan, dessen Ende die Uhr verpasst hat, klingelt
    /// so nie Stunden später nach; ein schon klingelnder Plan behält seine Weckart bis `activeAfterTarget`.
    public static let planAfterTarget: TimeInterval = 60

    private static let header = "phasewake-transient 1"
    private static let separator = "|"

    /// Probewecker ab `now`: Fenster in 1 Minute, Zielzeit in 2 Minuten. Weckart wie `base` (der nächste
    /// echte Wecker) samt Sonnenaufgang; ohne `base` die Vorgaben. Beenden immer durch Halten.
    public static func test(base: AlarmSpec?, now: Date, calendar: Calendar, uuid: String) -> TransientPlan {
        let plan = WakePlan(alarmId: testPrefix + uuid, windowStart: now.addingTimeInterval(testWindowStartSeconds),
                            target: now.addingTimeInterval(testTargetSeconds))
        let spec = specFor(plan, base: base, calendar: calendar, sunrise: base?.sunrise ?? .off, sunriseDevice: base?.sunriseDevice ?? .phone)
        return TransientPlan(kind: .test, spec: spec, plan: plan)
    }

    /// Powernap ab `now` mit `minutes` Dauer (auf `napMinMinutes`–`napMaxMinutes` begrenzt), Weckart wie `base`, ohne Licht.
    public static func nap(base: AlarmSpec?, now: Date, calendar: Calendar, minutes: Int, uuid: String) -> TransientPlan {
        let length = min(napMaxMinutes, max(napMinMinutes, minutes))
        let target = now.addingTimeInterval(TimeInterval(length * 60))
        let plan = WakePlan(alarmId: napPrefix + uuid, windowStart: target.addingTimeInterval(-TimeInterval(napWindowMinutes(length) * 60)),
                            target: target)
        return TransientPlan(kind: .nap, spec: specFor(plan, base: base, calendar: calendar, sunrise: .off, sunriseDevice: .phone), plan: plan)
    }

    /// Fenster eines Powernaps in Minuten: bis `napShortLimitMinutes` kurz, sonst `napWindowMinutes`.
    public static func napWindowMinutes(_ minutes: Int) -> Int {
        minutes <= napShortLimitMinutes ? napShortWindowMinutes : napWindowMinutes
    }

    /// Art nach der Kennung; `nil` für gespeicherte Wecker (oder ohne Kennung).
    public static func kindOf(_ alarmId: String?) -> TransientKind? {
        guard let alarmId else { return nil }
        if alarmId.hasPrefix(testPrefix) { return .test }
        if alarmId.hasPrefix(napPrefix) { return .nap }
        return nil
    }

    /// Ob Schlummern erlaubt ist: nicht beim Probewecker (er prüft genau einen Weckvorgang).
    public static func allowsSnooze(_ alarmId: String?) -> Bool { kindOf(alarmId) != .test }

    /// Ob der Plan noch gilt: bis `activeAfterTarget` nach der Zielzeit.
    public static func isActive(_ transient: TransientPlan, now: Date) -> Bool {
        now < transient.plan.target.addingTimeInterval(activeAfterTarget)
    }

    /// Ob der Plan noch geplant wird: bis `planAfterTarget` nach der Zielzeit.
    public static func isPlannable(_ transient: TransientPlan, now: Date) -> Bool {
        now < transient.plan.target.addingTimeInterval(planAfterTarget)
    }

    /// Welcher Plan als Nächstes gilt: `real` (nächster echter Wecker) oder `transient`, solange dieser noch geplant
    /// wird (`isPlannable`); der mit der früheren Zielzeit, bei Gleichstand die kleinere Kennung. Auf allen Geräten
    /// gleich: Ein Powernap verdrängt nie einen früheren echten Wecker. `nil`, wenn keiner gilt.
    public static func choose(real: WakePlan?, transient: TransientPlan?, now: Date) -> WakePlan? {
        let extra = transient.flatMap { isPlannable($0, now: now) ? $0.plan : nil }
        return [real, extra].compactMap { $0 }.min { ($0.target, $0.alarmId) < ($1.target, $1.alarmId) }
    }

    /// Ob der neue Plan `transient` (gerade erzeugt, Start `now`) abgelehnt wird. `next`: nächster echter Plan
    /// (`AlarmSchedule.earliestPlan` mit erledigten Nächten); `nightActive`: eine Nacht klingelt oder schlummert.
    /// - `alarmRunning`: `nightActive`, oder `now` liegt im Fenster von `next` (Fensterbeginn bis Zielzeit) – sonst
    ///   bräche der Plan die Messung des echten Weckers ab.
    /// - `alarmSoon`: Die Zielzeit von `transient` liegt nach dem Fensterbeginn von `next`; der Plan überdeckte das Fenster.
    /// Sonst `nil`.
    public static func conflict(_ transient: TransientPlan, next: WakePlan?, nightActive: Bool, now: Date) -> TransientConflict? {
        if nightActive { return .alarmRunning }
        guard let next else { return nil }
        if now >= next.windowStart && now < next.target { return .alarmRunning }
        return transient.plan.target > next.windowStart ? .alarmSoon : nil
    }

    /// Schreibt den Plan: Kopfzeile, «ART|fensterbeginnMs|zielzeitMs», danach der Wecker im Format von
    /// `Codec.encodeAlarms`. Inhalt der Nachrichten an die Uhr und des Speichers.
    public static func encode(_ transient: TransientPlan) -> String {
        header + "\n"
            + [transient.kind.rawValue, String(AlarmSchedule.millis(transient.plan.windowStart)), String(AlarmSchedule.millis(transient.plan.target))]
            .joined(separator: separator) + "\n"
            + Codec.encodeAlarms([transient.spec])
    }

    /// Liest `encode`.
    /// - Throws: ``CodecError`` bei fremdem oder kaputtem Format, oder wenn Art und Kennung nicht zusammenpassen.
    public static func decode(_ text: String) throws -> TransientPlan {
        let lines = text.components(separatedBy: "\n")
        guard lines.first == header else { throw CodecError(description: "Unbekanntes Format des vorübergehenden Plans") }
        let fields = (lines.count > 1 ? lines[1] : "").components(separatedBy: separator)
        guard let kind = TransientKind(rawValue: fields[0]) else { throw CodecError(description: "Art fehlt") }
        guard fields.count > 1, let start = Int64(fields[1]) else { throw CodecError(description: "Fensterbeginn fehlt") }
        guard fields.count > 2, let target = Int64(fields[2]) else { throw CodecError(description: "Zielzeit fehlt") }
        guard let spec = try Codec.decodeAlarms(lines.dropFirst(2).joined(separator: "\n")).first else { throw CodecError(description: "Wecker fehlt") }
        guard kindOf(spec.id) == kind, start <= target else { throw CodecError(description: "Plan passt nicht zur Kennung") }
        return TransientPlan(kind: kind, spec: spec, plan: WakePlan(alarmId: spec.id, windowStart: date(start), target: date(target)))
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: TimeInterval(ms) / 1000) }

    /// Wecker zum Plan: Weckart aus `base`, Zielzeit als Uhrzeit, einmalig, ohne Aufgabe und Namen.
    private static func specFor(_ plan: WakePlan, base: AlarmSpec?, calendar: Calendar, sunrise: Sunrise, sunriseDevice: SunriseDevice) -> AlarmSpec {
        let local = calendar.dateComponents([.hour, .minute], from: plan.target)
        // Stunde 0–23 und Minute 0–59 aus dem Kalender, Mindestfenster: Die Prüfung kann nicht scheitern.
        return try! AlarmSpec(
            id: plan.alarmId, hour: local.hour ?? 0, minute: local.minute ?? 0, windowMinutes: AlarmSpec.minWindowMinutes,
            phone: base?.phone ?? .sound, watchSound: base?.watchSound ?? false, sound: base?.sound ?? "",
            sunrise: sunrise, sunriseDevice: sunriseDevice
        )
    }
}
