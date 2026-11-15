import PhaseWakeCore
import SwiftUI

/// Text einer Auffälligkeit (Entscheid r1AD 15.11.2026): was auffiel, bei wenig Tiefschlaf der belegte Zusammenhang, und
/// ein Tipp. Gleich auf der Startseite (``FindingHomeCard``) und in den Trends des Verlaufs; wie `findingText` (Android).
func findingText(_ finding: Finding) -> String {
    switch finding.kind {
    case .longLatency: return L("finding_latency", Format.duration(minutes: finding.value))
    case .shortNights: return L("finding_short_nights", finding.value)
    case .lowDeep:
        let typical = StageStats.typical[.deep] ?? StageRange(low: 13, high: 23)
        var parts = [L("finding_low_deep", finding.value, typical.low, typical.high)]
        switch finding.link {
        case .shortNights: parts.append(L("finding_deep_short", finding.withPercent, finding.withoutPercent))
        case .lateBedtime: parts.append(L("finding_deep_late", finding.withPercent, finding.withoutPercent))
        case nil: break
        }
        parts.append(L("finding_tip_deep"))
        return parts.joined(separator: " ")
    case .muchAwake: return L("finding_awake", finding.value)
    case .socialJetlag: return L("finding_jetlag", Format.duration(minutes: finding.value))
    case .bedtimeVaries: return L("finding_bedtime", Format.duration(minutes: finding.value))
    }
}

/// «Tiefschlaf 11 % deines Schlafs · Vorwoche 13 %» für den Wochenrückblick, wie `weeklyDeepLine` (Android).
func weeklyDeepLine(_ deep: (week: Int, before: Int)) -> String {
    L("weekly_deep", L("stages_share", deep.week)) + " · " + L("trends_feeling_change", L("stages_share", deep.before))
}

/// Höchstens eine Auffälligkeit auf der Startseite, die wichtigste (``Findings/home(_:hidden:now:)``); «Ausblenden»
/// verbirgt sie 7 Tage (Zeitpunkt in ``SettingsFile/phone``, nicht in der iCloud-Sicherung). Liest Apple Health selbst
/// (nur mit Freigabe, nur im Speicher) beim Öffnen und bei jeder Rückkehr in den Vordergrund.
struct FindingHomeCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("healthRequested") private var healthRequested = false
    @PrivateSetting("findingsHidden") private var hidden = ""
    @State private var findings: [Finding] = []

    var body: some View {
        Group {
            if let finding = Findings.home(findings, hidden: Findings.decodeHidden(hidden), now: Date()) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(L("finding_title")).font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    Text(findingText(finding)).font(Fonts.manrope(14, relativeTo: .subheadline))
                    Text(L("stages_not_medical")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(Brand.tertiaryText(scheme))
                    HStack {
                        Spacer()
                        Button(L("finding_hide")) {
                            hidden = Findings.encodeHidden(Findings.hide(Findings.decodeHidden(hidden), kind: finding.kind, now: Date()))
                        }
                        .font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
                    }
                }
                .card()
            }
        }
        .task(id: "\(scenePhase == .active)|\(healthRequested)|\(model.historyClearedAt?.timeIntervalSince1970 ?? 0)") { await reload() }
    }

    /// Liest Apple Health und rechnet die Auffälligkeiten bis heute (Nachtdatum von «jetzt»).
    private func reload() async {
        guard healthRequested, scenePhase == .active else { return }
        let sessions = await model.health.sessions().sessions
        let calendar = Calendar.gregorianLocal
        let facts = NightFacts.of(sessions, clearedAt: model.historyClearedAt, calendar: calendar)
        findings = Findings.of(facts, today: SleepNotes.nightDate(Date(), calendar: calendar), calendar: calendar)
    }
}
