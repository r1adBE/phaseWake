import PhaseWakeCore
import SwiftUI

// Kompakte Zeilen der Einträge einer aufgeklappten Woche: eine Zeile je Nacht statt der ganzen Karte. Wie
// `HistoryCompactRows.kt` (Android).

/// Was die kompakten Zeilen brauchen: Schlaf je Nachtdatum, Befinden zu Fremd-Nächten, Schlafziel, Aufzeichnungen,
/// die Phasen einer eigenen Nacht und das Öffnen der Detailseite (Schlüssel ``HistoryItem/key``).
struct CompactRowsContext {
    let asleep: [String: Int]
    let healthFeelings: [String: Int]
    let goalMinutes: Int
    let recordings: [NightRecording]
    let stages: (NightRecord) -> [StageSpan]
    let open: (String) -> Void
}

/// Breite der Spalte für den Schlafwert-Ring; ohne Wert bleibt sie leer, damit die Daten bündig stehen.
private let compactLead: CGFloat = 36

/// Ein Eintrag einer aufgeklappten Woche als kompakte Zeile.
struct HistoryCompactEntry: View {
    let entry: HistoryEntry
    let context: CompactRowsContext
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        switch entry {
        case .night(let night):
            let date = SleepNotes.nightDate(of: night, calendar: .gregorianLocal)
            let score = nightScore(stages: StageStats.forNight(context.stages(night), night: night), session: nil,
                                   goalMinutes: context.goalMinutes, night: night,
                                   recordings: context.recordings)?.result
            CompactNightRow(day: compactDay(night.wokeAt, in: nil), wake: Format.time(night.wokeAt),
                            asleepMinutes: context.asleep[date], feeling: night.feeling, score: score,
                            health: false) {
                context.open(HistoryItem(kind: .phaseWake, date: date, night: night).key)
            }
        case .health(let session, let date):
            let score = nightScore(stages: StageStats.forSession(session), session: session,
                                   goalMinutes: context.goalMinutes)?.result
            CompactNightRow(day: compactDay(session.end, in: session.timeZone),
                            wake: Format.time(session.end, in: session.timeZone),
                            asleepMinutes: context.asleep[date] ?? session.asleepMinutes,
                            feeling: context.healthFeelings[date], score: score, health: true) {
                context.open(HistoryItem(kind: .health, date: date, session: session).key)
            }
        case .sounds(let night, let last):
            // Bilanz der Geräusche; ein Tipp öffnet die Ausschnitte (Abspielen, Löschen) wie auf der Karte.
            CompactLayout(day: compactDay(last, in: nil)) {
                NightSoundsButton(night: night, title: last.formatted(date: .abbreviated, time: .omitted))
            }
        case .recording(let recording):
            CompactLayout(day: compactDay(recording.start, in: nil),
                          trailing: Format.time(recording.start) + " – " + Format.time(recording.until)) {
                Text(L("record_title") + " · " + Format.duration(minutes: Int(recording.duration / 60)) + " · "
                     + recordEndText(recording.endReason))
                    .font(Fonts.manrope(12, relativeTo: .caption))
                    .foregroundStyle(Brand.secondaryText(scheme))
            }
            .accessibilityElement(children: .combine)
        }
    }
}

/// Kompakte Nachtzeile: links der Ring des Schlafwerts, daneben Tag und darunter «7 Std. 6 Min · Befinden: Gut» (aus
/// Apple Health mit Herz-Symbol, nicht nur Farbe), rechts die Weckzeit und ein Pfeil. Für VoiceOver ein Knopf mit
/// Tag, Weckzeit, Dauer, Schlafwert und Befinden; ein Tipp öffnet die Detailseite.
private struct CompactNightRow: View {
    let day: String
    let wake: String
    let asleepMinutes: Int?
    let feeling: Int?
    let score: SleepScoreResult?
    let health: Bool
    let onOpen: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let duration = asleepMinutes.map { Format.duration(minutes: $0) }
        let feelingText = feeling.map { L("feeling_value", feelingName($0)) }
        let detail = [duration, feelingText].compactMap { $0 }.joined(separator: " · ")
        let label = [day, wake, duration, score.map(scoreDescription), feelingText,
                     health ? L("history_health_mark") : nil].compactMap { $0 }.joined(separator: ", ")
        Button(action: onOpen) {
            CompactLayout(day: day, trailing: wake, trailingTint: !health, chevron: true, score: score) {
                if !detail.isEmpty {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        if health { Image(systemName: "heart").imageScale(.small) }
                        Text(detail)
                    }
                    .font(Fonts.manrope(12, relativeTo: .caption))
                    .foregroundStyle(Brand.secondaryText(scheme))
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityHint(L("night_open"))
        .accessibilityAddTraits(.isButton)
    }
}

/// Gerüst einer kompakten Zeile: Spalte für den Ring (`score` oder leer), Tag und darunter `detail`, rechts `trailing`
/// (Weckzeit in der Akzentfarbe mit `trailingTint`) und mit `chevron` ein Pfeil. Mindestens 56 pt hoch.
private struct CompactLayout<Detail: View>: View {
    let day: String
    var trailing: String?
    var trailingTint = false
    var chevron = false
    var score: SleepScoreResult?
    @ViewBuilder let detail: () -> Detail
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                if let score { ScoreRing(result: score, size: compactLead, fontSize: 12) }
            }
            .frame(width: compactLead, height: compactLead)
            VStack(alignment: .leading, spacing: 2) {
                Text(day).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                detail()
            }
            Spacer(minLength: 0)
            if let trailing {
                Text(trailing)
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    .foregroundStyle(trailingTint ? AnyShapeStyle(.tint) : AnyShapeStyle(Brand.secondaryText(scheme)))
            }
            if chevron {
                Image(systemName: "chevron.forward")
                    .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                    .foregroundStyle(Brand.secondaryText(scheme))
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 14)
        .padding(.vertical, 8)
        .frame(minHeight: 56)
        .contentShape(Rectangle())
    }
}

/// «Di., 14.10.»: Tag des Morgens mit kurzem Wochentag (wie ``Format/shortDay(_:)``), in `zone` (`nil` = Gerät).
private func compactDay(_ date: Date, in zone: TimeZone?) -> String {
    var style = Date.FormatStyle().weekday(.abbreviated).day().month(.defaultDigits)
    if let zone { style.timeZone = zone }
    return date.formatted(style)
}
