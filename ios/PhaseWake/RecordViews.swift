import PhaseWakeCore
import SwiftUI

/// «Nacht aufzeichnen» auf der Startseite (wie `RecordCard` auf Android), nur mit einer Apple Watch, die es kennt
/// (`WatchNight.watchCanRecord`): gemessen wird auf der Uhr, das iPhone schickt nur Start und Stopp. Symbol und Titel in
/// einer Zeile, darunter der Hinweis bzw. «Aufzeichnung läuft seit …», dann der Knopf rechts. Nur, solange die Lernphase
/// nicht läuft (fertig oder aus), mit kurzem Hinweis; während der Lernphase steckt der Knopf in `LearningHomeCard`
/// (r1AD 13.11.2026).
struct RecordCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let night = WatchNight.shared
        let learning = PhoneLearning.shared
        let inLearningCard = learning.watchCanLearn(model) && learning.progress != nil
        // Solange die Frage «Lernphase starten / Später» offen ist, keine eigene Karte – ausser es läuft schon eine
        // Aufzeichnung (Stopp erreichbar), wie Android (r1AD 13.11.2026).
        let asking = learning.state.askConsent && learning.watchCanLearn(model) && night.recordSince == nil
        if night.watchCanRecord(model) && !inLearningCard && !asking {
            let since = night.recordSince
            VStack(alignment: .leading, spacing: 8) {
                Label(L("record_title"), systemImage: "applewatch")
                    .font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                    .accessibilityAddTraits(.isHeader)
                Text(since.map { L("record_since", Format.time($0)) } ?? L("record_hint"))
                    .font(Fonts.manrope(14, relativeTo: .subheadline))
                    .foregroundStyle(Brand.secondaryText(scheme))
                RecordButton()
                    .frame(maxWidth: .infinity, alignment: .trailing)
                if night.recordUnanswered { RecordUnansweredHint() }
            }
            .card()
        }
    }
}

/// «Nacht aufzeichnen» bzw. «Aufzeichnung stoppen» (Karte «Nacht-Aufzeichnung» und Karte der Lernphase).
struct RecordButton: View {
    var body: some View {
        let night = WatchNight.shared
        let since = night.recordSince
        Button(L(since != nil ? "record_stop" : "record_start")) { night.setRecording(since == nil) }
            .buttonStyle(.bordered)
            .disabled(night.recordPending)
    }
}

/// Keine Antwort der Uhr (App dort wohl zu): ehrlich sagen statt still wieder freigeben (Audit AP-8).
struct RecordUnansweredHint: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Label(L("watch_open_app_hint"), systemImage: "applewatch")
            .font(Fonts.manrope(14, relativeTo: .subheadline))
            .foregroundStyle(Brand.secondaryText(scheme))
    }
}

/// Text zum Ende einer Aufzeichnung (wie `recordEndRes`, Android).
func recordEndText(_ reason: RecordEnd?) -> String {
    switch reason {
    case .watch, .phone: L("record_end_manual")
    case .cap: L("record_end_cap")
    case .battery: L("record_end_battery")
    case .service, nil: L("record_end_interrupted")
    case .wake: L("record_end_wake")
    }
}

/// Aufzeichnung im Verlauf, schlicht (wie `RecordingCard`, Android): Datum, Beginn bis Ende, Titel, Dauer und Grund des
/// Endes. Die Werte selbst stehen im Export und als Kurven auf der Detailseite der Nacht, die sie überdeckt.
struct RecordingCard: View {
    let recording: NightRecording
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(recording.start.formatted(date: .abbreviated, time: .omitted))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                Spacer()
                Text(Format.time(recording.start) + " – " + Format.time(recording.until))
                    .font(Fonts.manrope(16, .bold, relativeTo: .headline))
                    .foregroundStyle(.tint)
            }
            Text(L("record_title")).font(Fonts.manrope(14, relativeTo: .subheadline))
            Text(Format.duration(minutes: Int(recording.duration / 60)) + " · " + recordEndText(recording.endReason))
                .font(Fonts.manrope(12, relativeTo: .caption))
                .foregroundStyle(Brand.secondaryText(scheme))
        }
        .card()
        .accessibilityElement(children: .combine)
    }
}
