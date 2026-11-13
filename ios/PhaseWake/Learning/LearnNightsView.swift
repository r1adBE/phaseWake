import PhaseWakeCore
import SwiftUI

/// Lern-Übersicht am iPhone (Entscheid r1AD 13.11.2026, wie `LearnNightsScreen` auf Android; aus «Details» der Karte der
/// Lernphase auf der Startseite und in den Einstellungen): je Nacht seit dem Beginn eine Zeile mit Datum (Morgen der
/// Nacht), ✓ gezählt bzw. ✗ mit Grund, kurzen Angaben zur Aufzeichnung und einem Tipp. Für VoiceOver eine Einheit je Zeile.
struct LearnNightsView: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let nights = Self.nights(model: model, now: Date())
        ZStack {
            if scheme == .dark { NightSky().ignoresSafeArea() } else { Brand.paper.ignoresSafeArea() }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    Text(L("learn_nights_intro", Learning.nights))
                        .font(Fonts.manrope(14, relativeTo: .subheadline))
                        .foregroundStyle(Brand.secondaryText(scheme))
                    if nights.isEmpty {
                        Text(L("learn_nights_empty")).font(Fonts.manrope(15, relativeTo: .body))
                    }
                    ForEach(nights) { LearnNightRow(night: $0) }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
        }
        .navigationTitle(L("learn_consent_title"))
    }

    /// Die Nächte seit dem Beginn der Lernphase, jüngste zuerst (``LearnNights/nights(_:skips:alarmNights:alarmsKnownFrom:since:consent:autoOnset:now:calendar:)``):
    /// Aufzeichnungen der Uhr, ihre Meldungen, die gemerkten Nächte mit Wecker und die Nächte der Uhr (sie belegen einen
    /// Wecker). Die Apple Watch zeichnet unabhängig von «Einschlafen automatisch erkennen» auf (Schalter gilt als ein).
    static func nights(model: PhoneModel, now: Date) -> [LearnNights.Night] {
        let learning = PhoneLearning.shared
        guard let since = learning.state.since else { return [] }
        let log = LearnLogStore.shared
        let calendar = Calendar.gregorianLocal
        var alarms = Set(log.alarmNights.keys)
        for night in model.nights { alarms.insert(SleepNotes.nightDate(of: night, calendar: calendar)) }
        let recordings = learning.recordings.map { LearnNights.Recorded(night: $0.learnNight, endCode: $0.endReason?.rawValue) }
        return LearnNights.nights(recordings, skips: log.skips, alarmNights: alarms, alarmsKnownFrom: log.alarmsKnownFrom,
                                  since: since, consent: learning.state.consent == true, autoOnset: true, now: now, calendar: calendar)
    }

    /// Grund einer Nacht in Worten (Übersicht und Startseite), wie `learnReasonText` (Android).
    static func reason(_ night: LearnNights.Night) -> String {
        switch night.reason {
        case .counted: return L("learn_reason_counted")
        case .countedNoPulse: return L("learn_reason_counted_no_pulse")
        case .tooShort:
            let needed = Learning.minSpanEpochs * DetectorConfig.epochSeconds / 60
            return L("learn_reason_too_short", Format.duration(minutes: night.minutes ?? 0), Format.duration(minutes: needed))
        case .tooFew: return L("learn_reason_too_few", Int64(night.measuredEpochs ?? 0), Int64(Learning.minMeasuredEpochs))
        case .endedStopped: return L("learn_reason_stopped")
        case .endedBattery: return L("learn_reason_ended_battery")
        case .endedService: return L("learn_reason_ended_system")
        case .notWorn: return L("learn_reason_not_worn")
        case .battery: return night.battery.map { L("learn_reason_battery", Int64($0)) } ?? L("learn_reason_battery_low")
        case .sensors: return L("learn_reason_sensors")
        case .manual: return L("learn_reason_manual")
        case .late: return L("learn_reason_late")
        case .learningOff: return L("learn_reason_off")
        case .noAlarm: return L("learn_reason_no_alarm")
        case .pending: return L("learn_reason_pending")
        case .autoOff: return L("learn_reason_auto_off")
        case .noRecording, .unknown: return L("learn_reason_none")
        }
    }

    /// Kurze Angaben zur Aufzeichnung (gemessene Spanne, Messwerte, Pulsanteil); `nil` ohne Aufzeichnung.
    static func facts(_ night: LearnNights.Night) -> String? {
        // «Zu kurz» nennt die gemessene Spanne schon im Grund: keine zweite Zeile mit denselben Minuten.
        guard night.reason != .tooShort, let minutes = night.minutes else { return nil }
        return L("learn_night_facts", Format.duration(minutes: minutes), Int64(night.measuredEpochs ?? 0), Int64(night.pulsePercent ?? 0))
    }

    /// Kurzer Tipp zum Grund; `nil`, wenn es nichts zu tun gibt. Ohne Aufzeichnung auf der Apple Watch zusätzlich: die App
    /// am Abend öffnen (sonst startet der Rekorder nicht).
    static func tip(_ reason: LearnNights.Reason) -> String? {
        switch reason {
        case .counted, .learningOff: return nil
        case .countedNoPulse, .notWorn: return L("learn_tip_worn")
        case .tooShort, .endedStopped: return L("learn_tip_run")
        case .tooFew, .endedService: return L("learn_tip_system")
        case .endedBattery, .battery: return L("learn_tip_charge", Int64(Learning.minBattery))
        case .sensors: return L("learn_tip_sensors")
        case .late: return L("learn_tip_late")
        case .noAlarm: return L("learn_tip_alarm")
        case .autoOff: return L("learn_auto_hint")
        case .manual, .pending: return L("learn_tip_connect")
        case .noRecording, .unknown: return L("learn_tip_connect") + " " + L("learn_tip_late")
        }
    }
}

/// Eine Nacht der Lern-Übersicht: Datum, ✓/✗, Grund, Angaben, Tipp. Mindestens 48 pt hoch.
private struct LearnNightRow: View {
    let night: LearnNights.Night
    @Environment(\.colorScheme) private var scheme

    /// Gründe ohne Fehler (✗ gedämpft statt rot): erwartet bzw. noch offen.
    private static let expected: Set<LearnNights.Reason> = [.noAlarm, .pending, .learningOff]

    var body: some View {
        let ok = night.reason.isCounted
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle")
                .font(.system(size: 20))
                .foregroundStyle(ok ? Brand.accent(scheme) : Self.expected.contains(night.reason) ? Brand.secondaryText(scheme) : Color.red)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(Format.shortDay(NightDate.adding(1, to: night.date)))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Text(LearnNightsView.reason(night)).font(Fonts.manrope(14, relativeTo: .subheadline))
                if let facts = LearnNightsView.facts(night) {
                    Text(facts).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.secondaryText(scheme))
                }
                if let tip = LearnNightsView.tip(night.reason) {
                    Text(tip).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.accent(scheme))
                }
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 48)
        .card()
        .accessibilityElement(children: .combine)
    }
}
