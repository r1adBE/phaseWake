import PhaseWakeCore
import SwiftUI

/// Karte der Lernphase auf der Startseite (wie `LearningCard` auf Android), nur mit Zustimmung (Audit F-02): noch nicht
/// gefragt und die Uhr kann aufzeichnen – einmal «Lernphase starten» / «Später»; solange sie läuft «Lernphase: Nacht N
/// von 7» mit kurzer Erklärung, einer unerwartet nicht gezählten letzten Nacht mit Grund und «Details» zur
/// Lern-Übersicht (r1AD 13.11.2026); danach einmal «Angepasst an dich» mit den gelernten Werten und «OK». Sonst nichts.
struct LearningHomeCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let learning = PhoneLearning.shared
        let state = learning.state
        let watch = learning.watchCanLearn(model)
        if state.askConsent && watch {
            card(L("learn_consent_title")) {
                LearningConsentView(color: Brand.secondaryText(scheme)) { learning.setConsent($0) }
            }
        } else if watch, let night = learning.progress {
            card(L("learn_progress", night, Learning.nights)) {
                Text(L("learn_text", Learning.nights, Learning.minBattery))
                    .font(Fonts.manrope(14, relativeTo: .subheadline))
                    .foregroundStyle(Brand.secondaryText(scheme))
                let now = Date()
                if let miss = LearnNights.lastNightMiss(LearnNightsView.nights(model: model, now: now), now: now, calendar: .gregorianLocal) {
                    LastNightMissLine(miss: miss)
                }
                NavigationLink(L("night_details")) { LearnNightsView() }
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .trailing)
            }
        } else if let values = state.values, !state.active, state.consent == true, !state.seen {
            card(L("learned_title")) {
                LearnedLines(values: values, color: Brand.secondaryText(scheme))
                Button(L("wake_task_check")) { learning.setSeen() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private func card<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(title, systemImage: "applewatch")
                .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                .accessibilityAddTraits(.isHeader)
            content()
        }
        .card()
    }
}

/// «Letzte Nacht zählte nicht» mit dem Grund (``LearnNightsView/reason(_:)``), wie auf Android.
private struct LastNightMissLine: View {
    let miss: LearnNights.Night
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle").foregroundStyle(.red).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(L("learn_last_night")).font(Fonts.manrope(14, .semibold, relativeTo: .subheadline))
                Text(LearnNightsView.reason(miss))
                    .font(Fonts.manrope(14, relativeTo: .subheadline))
                    .foregroundStyle(Brand.secondaryText(scheme))
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Frage zur Lernphase (Einrichtung und Startseite): was die Uhr aufzeichnet, Akku, Daten, abschaltbar; dann «Später»
/// (`onConsent(false)`) und «Lernphase starten» (`true`).
struct LearningConsentView: View {
    let color: Color
    let onConsent: (Bool) -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("learn_consent_text", Learning.nights))
                .font(Fonts.manrope(14, relativeTo: .subheadline))
                .foregroundStyle(color)
            HStack {
                Spacer()
                Button(L("learn_consent_later")) { onConsent(false) }
                    .buttonStyle(.bordered)
                Button(L("learn_consent_start")) { onConsent(true) }
                    .buttonStyle(.borderedProminent)
                    .tint(Brand.accent(scheme))
                    .foregroundStyle(Brand.onAccent(scheme))
            }
        }
    }
}

/// Gelernte Werte in Worten: Bewegungsschwellen, Ruhepuls, Einschlafdauer (Startseite und Einstellungen).
struct LearnedLines: View {
    let values: Learning.Values
    var color: Color = .secondary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L(Learning.raisesThresholds(values.floor) ? "learned_motion_raised" : "learned_motion_default"))
            Text(values.restingHeartRate.map { L("learned_pulse", Int($0.rounded())) } ?? L("learned_pulse_none"))
            Text(values.latencyMinutes.map { L("learned_latency", Format.duration(minutes: $0)) } ?? L("learned_latency_none"))
        }
        .font(Fonts.manrope(14, relativeTo: .subheadline))
        .foregroundStyle(color)
    }
}

/// Empfohlene Schlafenszeit am Abend (nach der Lernphase, wie `BedtimeAdviceCard` auf Android): «Geh heute zwischen …
/// und … ins Bett» für den Wecker dieser Nacht, bis zum Ende der Spanne (``Learning/bedtimeRange(target:sleepMinutes:latencyMinutes:)``;
/// Schlaf = Schlafdauer des Weckers, sonst das Schlafziel). Ohne gelernte Werte nichts.
struct BedtimeAdviceCard: View {
    let now: Date
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if let range = Self.range(model: model, values: PhoneLearning.shared.state.values, now: now) {
            HStack(spacing: 14) {
                Image(systemName: "moon.stars").foregroundStyle(.tint).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("bedtime_advice", Format.time(range.from), Format.time(range.until)))
                        .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    Text(L("bedtime_advice_hint"))
                        .font(Fonts.manrope(12, relativeTo: .caption))
                        .foregroundStyle(Brand.secondaryText(scheme))
                }
            }
            .card()
            .accessibilityElement(children: .combine)
        }
    }

    /// Spanne für den Wecker dieser Nacht; `nil` ohne gelernte Werte, ohne Wecker heute Nacht oder nach ihrem Ende.
    static func range(model: PhoneModel, values: Learning.Values?, now: Date) -> (from: Date, until: Date)? {
        guard let values, let plan = model.nextPlan(now: now),
              SleepNotes.nightDate(plan.windowStart, calendar: .gregorianLocal) == SleepNotes.nightDate(now, calendar: .gregorianLocal),
              let alarm = model.alarms.first(where: { $0.id == plan.alarmId }) else { return nil }
        let range = Learning.bedtimeRange(target: plan.target, sleepMinutes: alarm.sleepDurationMinutes ?? model.sleepGoal,
                                          latencyMinutes: values.latencyMinutes)
        return now < range.until ? range : nil
    }
}

/// Lernphase in den Einstellungen (wie `LearningGroup` auf Android): Schalter «Lernphase» (Zustimmung), «Nacht N von 7»
/// bzw. die gelernten Werte, «Details» zur Lern-Übersicht und «Neu lernen» mit Rückfrage. Nur mit einer Uhr, die aufzeichnen kann, oder wenn schon
/// gelernt.
struct LearningSettingsSection: View {
    @Environment(PhoneModel.self) private var model
    @State private var confirm = false

    var body: some View {
        let learning = PhoneLearning.shared
        let state = learning.state
        if state.values != nil || learning.watchCanLearn(model) {
            let night = learning.progress
            Section(state.values != nil ? L("learned_title") : night.map { L("learn_progress", $0, Learning.nights) } ?? L("learn_consent_title")) {
                Toggle(L("learn_consent_title"), isOn: Binding(get: { learning.state.consent == true }, set: { learning.setConsent($0) }))
                Text(L("learn_switch_hint")).font(.footnote).foregroundStyle(.secondary)
                if let values = state.values {
                    LearnedLines(values: values)
                } else if night != nil {
                    Text(L("learn_text", Learning.nights, Learning.minBattery)).font(.footnote)
                }
                if state.since != nil {
                    NavigationLink(L("night_details")) { LearnNightsView() }
                    Button(L("learn_restart")) { confirm = true }
                }
            }
            .alert(L("learn_restart"), isPresented: $confirm) {
                Button(L("action_cancel"), role: .cancel) {}
                Button(L("learn_restart")) { learning.relearn() }
            } message: {
                Text(L("learn_restart_text", Learning.nights))
            }
        }
    }
}

/// Einrichtungsschritt «Lernphase» (zwischen erstem Wecker und Probewecker, wie Android): kurze Einleitung und drei
/// Punkte (Audit U-2); «Später» / «Lernphase starten» stehen unten in der Navigation statt «Weiter». Nach dem Start
/// «✓ Erlaubt».
struct LearningOnboardingStep: View {
    var body: some View {
        Text(L("learn_intro"))
        VStack(alignment: .leading, spacing: 8) {
            ForEach([L("learn_point_nights", Learning.nights), L("learn_point_battery"), L("learn_point_private")], id: \.self) { point in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(verbatim: "•").accessibilityHidden(true)
                    Text(point)
                }
            }
        }
        if PhoneLearning.shared.state.consent == true {
            Label(L("onb_granted"), systemImage: "checkmark.circle")
        }
    }
}
