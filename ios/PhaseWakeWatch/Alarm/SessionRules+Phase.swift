import Foundation
import PhaseWakeCore
import WatchKit

/// Entscheide zu Start, Übernahme und Ende der Smart-Alarm-Sitzung je nach Zustand der Nacht (testbar, ohne Sitzung):
/// ankommende Sitzung, Start, Neuplanen nach einer Reise und was ein Sitzungsende für die Nacht heisst.
extension SessionRules {
    // MARK: Ankommende Sitzung

    /// Was mit einer vom System gestarteten Sitzung geschieht (`SmartAlarm.adopt`).
    enum Adoption: Equatable {
        /// Gemerkte, nicht mehr gültige Sitzung: nichts übernehmen (sie endet bei ihrem Start oder übernimmt dann).
        case stale
        /// Die eigene geplante oder laufende Sitzung bleibt; die ankommende wird gemerkt und endet bei ihrem Start.
        case keepOwn
        /// Die eigene gilt nicht mehr (beenden); die ankommende wird die Sitzung der Nacht.
        case replaceOwn
        /// Keine andere eigene: Die ankommende wird die Sitzung der Nacht.
        case take
    }

    /// Entscheid für eine ankommende Sitzung: gemerkt (`stale`), sonst nach dem Zustand `own` der eigenen, anderen
    /// Sitzung (`nil`: keine oder dieselbe; nur gefragt, wenn sie nicht gemerkt ist), ``keepsOwn(_:)``.
    static func adoption(stale: Bool, own: @autoclosure () -> WKExtendedRuntimeSessionState?) -> Adoption {
        if stale { return .stale }
        guard let state = own() else { return .take }
        return keepsOwn(state) ? .keepOwn : .replaceOwn
    }

    // MARK: Start der Sitzung

    /// Was eine eben gestartete eigene Sitzung tut (`extendedRuntimeSessionDidStart`).
    enum Started: Equatable {
        /// Nach dem Schlummern: gleich wieder wecken.
        case ringAgain
        /// Misst oder klingelt schon: nichts.
        case alreadyRunning
        /// Sonst: Zeitzone prüfen und die Nacht beginnen (`startNight`).
        case startNight
    }

    /// Entscheid beim Start der Sitzung im Zustand `phase`.
    @MainActor
    static func started(_ phase: SmartAlarm.Phase) -> Started {
        switch phase {
        case .snoozed: .ringAgain
        case .measuring, .ringing: .alreadyRunning
        case .idle, .waiting, .scheduled: .startNight
        }
    }

    /// Ob eine Nacht gerade misst, klingelt oder schlummert: Dann übernimmt keine verwaiste Sitzung
    /// (``usesOrphan(own:busy:serves:)``).
    @MainActor
    static func busy(_ phase: SmartAlarm.Phase) -> Bool {
        phase.isMeasuring || phase.isRinging || phase.isSnoozed
    }

    // MARK: Reise

    /// Die Nacht, die nach einer Reise beim Start der Sitzung gehalten werden kann (`replansAfterZoneChange`): die
    /// geplante oder wartende. Sonst `nil` (nach einem Neustart der App `idle`: dann gilt die gemerkte).
    @MainActor
    static func plannedNight(_ phase: SmartAlarm.Phase) -> WakePlan? {
        switch phase {
        case .scheduled(let night), .waiting(let night): night
        case .idle, .measuring, .ringing, .snoozed: nil
        }
    }

    /// Was nach dem Neuplanen wegen einer Reise mit der eben gestarteten Sitzung geschieht.
    enum Replanned: Equatable {
        /// Sie ist weiter die geplante Sitzung der Nacht (`scheduled`): wie gewohnt beginnen.
        case proceed
        /// Sie gilt nicht mehr: beenden (es weckt der Rückfall zur Ortszeit).
        case endSession
        /// Eine andere Sitzung gilt: nichts mehr zu tun.
        case done
    }

    /// Entscheid nach dem Neuplanen: Steht die gestartete Sitzung noch für die Nacht (`sameSession`) und ist die Nacht
    /// geplant (`scheduled`)?
    static func replanned(scheduled: Bool, sameSession: Bool) -> Replanned {
        guard sameSession else { return .done }
        return scheduled ? .proceed : .endSession
    }

    // MARK: Ende der Sitzung

    /// Was ein Ende der eigenen Sitzung (`didInvalidateWith`) für die Nacht tut.
    enum AfterEnd: Equatable {
        /// Weckhinweis des Systems gestoppt: wie Stopp auf der Uhr – die Nacht endet ganz, das iPhone hört auf, die
        /// Kette fällt weg (docs/OFFEN.md 11u).
        case dismiss
        /// Geplante Sitzung vom System abgelehnt oder zurückgezogen: Der Plan wartet, der Rückfall weckt.
        case wait(WakePlan)
        /// Abbruch beim Messen (``interruptedWake(now:target:)``).
        case measuringInterrupted(WakePlan)
        /// Abgelaufen oder vom System beendet beim Klingeln oder Schlummern: weiterwecken und abschliessen.
        case finishInterrupted
        /// Keine Sitzung im Einsatz (`idle`, `waiting`): nichts.
        case nothing
    }

    /// Entscheid für das Ende der eigenen Sitzung im Zustand `phase` mit Bedeutung `ending` (``ending(_:ringing:)``).
    @MainActor
    static func afterEnd(_ phase: SmartAlarm.Phase, ending: Ending) -> AfterEnd {
        switch phase {
        case .ringing where ending == .dismissed: .dismiss
        case .scheduled(let plan): .wait(plan)
        case .measuring(let plan): .measuringInterrupted(plan)
        case .ringing, .snoozed: .finishInterrupted
        case .idle, .waiting: .nothing
        }
    }
}
