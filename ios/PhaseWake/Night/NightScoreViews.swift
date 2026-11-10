import PhaseWakeCore
import SwiftUI

// Schlafwert einer Nacht (Bogen mit Faktoren), Kurzzeile in der Liste und Messreihe der Detailseite. Wie `NightScore.kt`
// (Android).

/// Woher die Werte des Schlafwerts stammen: Phasen aus Apple Health bzw. Sitzung, oder Aufzeichnung der Uhr.
enum ScoreSource { case health, recording }

/// Schlafwert einer Nacht samt den Messwerten, aus denen er gerechnet ist.
struct NightScore {
    let result: SleepScoreResult
    let input: ScoreInput
    var source: ScoreSource = .health
}

/// Messreihe einer Nacht (Bewegung und Puls je 30-s-Epoche ab `start`); `fullNight`: ganze Aufzeichnung statt Weckfenster.
struct NightSeries {
    let epochs: [Epoch]
    let start: Date
    let fullNight: Bool
}

/// Abgeschlossene Aufzeichnung, die sich mit `from`–`to` am längsten überschneidet; keine: `nil` (wie `recordingFor`, Android).
func recordingFor(from: Date, to: Date, recordings: [NightRecording]) -> NightRecording? {
    recordings.filter { $0.finished && $0.start < to && $0.until > from }
        .max { overlap($0, from, to) < overlap($1, from, to) }
}

private func overlap(_ recording: NightRecording, _ from: Date, _ to: Date) -> TimeInterval {
    min(recording.until, to).timeIntervalSince(max(recording.start, from))
}

/// Zeitraum eines Eintrags: eigene Nacht Fensterbeginn bis Wecken, Sitzung Beginn bis Ende.
private func itemRange(_ item: HistoryItem) -> (from: Date, to: Date)? {
    if let night = item.night { return (from: night.windowStart, to: night.wokeAt) }
    if let session = item.session { return (from: session.start, to: session.end) }
    return nil
}

/// Schlafwert einer Nacht (``SleepScore``): mit Phasen aus diesen, bei einer Sitzung ohne Phasen aus ihrer Dauer, bei
/// einer eigenen Nacht ohne Phasen aus einer Aufzeichnung der Uhr mit Einschlafzeit (bis zum Wecken; vorläufig). Keine
/// Werte: `nil`. Wie `nightScore` (Android).
func nightScore(stages: NightStages?, session: HealthSession?, goalMinutes: Int, night: NightRecord? = nil,
                recordings: [NightRecording] = []) -> NightScore? {
    let input: ScoreInput
    var source = ScoreSource.health
    if let stages {
        input = SleepScore.inputOf(stages, goalMinutes: goalMinutes)
    } else if let session {
        input = ScoreInput(asleepMinutes: session.asleepMinutes, goalMinutes: goalMinutes)
    } else if let night,
              let recording = recordingFor(from: night.windowStart, to: night.wokeAt, recordings: recordings),
              let asleep = recording.asleepAt,
              let fromRecording = SleepScore.inputOf(bedAt: recording.bedAt, asleepAt: asleep, end: min(recording.until, night.wokeAt),
                                                     goalMinutes: goalMinutes) {
        input = fromRecording
        source = .recording
    } else {
        return nil
    }
    return SleepScore.of(input).map { NightScore(result: $0, input: input, source: source) }
}

/// Messreihe für Puls und Bewegung: überschneidende Aufzeichnung (ganze Nacht), sonst bei eigener Nacht das Weckfenster.
func nightSeries(_ item: HistoryItem, recordings: [NightRecording]) -> NightSeries? {
    if let span = itemRange(item), let recording = recordingFor(from: span.from, to: span.to, recordings: recordings) {
        return NightSeries(epochs: recording.epochs, start: recording.start, fullNight: true)
    }
    return item.night.map { NightSeries(epochs: $0.epochs, start: $0.windowStart, fullNight: false) }
}

/// Bogen von 240°, unten offen (wie der Weg des Mondes über den Nachthimmel); Beginn unten links.
private let arcStart = 150.0
private let arcSweep = 240.0

/// Schlafwert der Detailseite: Bogen mit dem Wert, der in einem Weckpunkt endet (Logo-Motiv), darunter die Bewertung
/// (ohne Schlafphasen mit dem Hinweis «vorläufig»); je Faktor eine Kachel. Unten Quelle und Gewichtung.
struct ScoreCard: View {
    let score: NightScore
    /// «Aus Apple Health.» o. ä.; `nil`: Der Kopf nennt die Quelle schon.
    let source: String?
    let goalMinutes: Int
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("score_title")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            ScoreArc(result: score.result).frame(maxWidth: .infinity)
            if score.result.provisional {
                // VoiceOver liest den Hinweis schon beim Bogen.
                Text(L("score_provisional"))
                    .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                    .foregroundStyle(Brand.secondaryText(scheme))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .accessibilityHidden(true)
            }
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(score.result.factors, id: \.factor) { factor in
                    FactorTile(factor: factor, input: score.input, goalMinutes: goalMinutes)
                }
            }
            Text(([source, L("score_weights")] as [String?]).compactMap { $0 }.joined(separator: " "))
                .font(Fonts.manrope(11, relativeTo: .caption2))
                .foregroundStyle(Brand.secondaryText(scheme))
        }
        .card()
    }
}

/// Grosser Bogen: Spur, Wert im Verlauf von der Nacht (tief) über Lavendel zum Morgenrot, Weckpunkt mit leichtem Schein
/// am Ende; Zahl und Bewertung in der Mitte. Läuft beim Öffnen einmal bis zum Wert (nicht bei «Bewegung reduzieren»).
private struct ScoreArc: View {
    let result: SleepScoreResult
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var fraction = 0.0

    private let stroke: CGFloat = 14
    private let dot: CGFloat = 7

    var body: some View {
        let palette = ChartPalette(scheme)
        let target = Double(result.score) / 100
        let gradient = AngularGradient(
            stops: [
                .init(color: StageColors.color(.deep, scheme), location: 0),
                .init(color: StageColors.color(.deep, scheme), location: 0.05),
                .init(color: palette.active, location: 0.5),
                .init(color: dawn(scheme), location: 1),
            ],
            center: .center, startAngle: .degrees(-12), endAngle: .degrees(arcSweep + 12)
        )
        ZStack {
            Circle().inset(by: stroke / 2 + dot / 2)
                .trim(from: 0, to: arcSweep / 360)
                .stroke(StageColors.track(scheme), style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                .rotationEffect(.degrees(arcStart))
            Circle().inset(by: stroke / 2 + dot / 2)
                .trim(from: 0, to: arcSweep / 360 * fraction)
                .stroke(gradient, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                .rotationEffect(.degrees(arcStart))
            ArcEnd(fraction: fraction, inset: stroke / 2 + dot / 2, radius: dot * 2.3).fill(palette.active.opacity(0.22))
            ArcEnd(fraction: fraction, inset: stroke / 2 + dot / 2, radius: dot + 2.5).fill(palette.active)
            ArcEnd(fraction: fraction, inset: stroke / 2 + dot / 2, radius: dot).fill(palette.dot)
            VStack(spacing: 0) {
                Text(result.score.formatted()).font(Fonts.manrope(44, .heavy, relativeTo: .largeTitle))
                Text(bandName(result.band)).font(Fonts.manrope(16, .semibold, relativeTo: .headline)).foregroundStyle(.tint)
            }
            .padding(.top, 8)
        }
        .frame(width: 190, height: 190)
        // Unten ist der Bogen offen: Platz darunter weglassen.
        .frame(height: 160, alignment: .top)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(scoreDescription(result))
        .onAppear {
            if reduceMotion { fraction = target } else { withAnimation(.easeInOut(duration: 0.9)) { fraction = target } }
        }
        .onChange(of: result.score) { _, value in fraction = Double(value) / 100 }
    }
}

/// Kreis am Ende des Bogens (Anteil `fraction` von 240°); wandert beim Animieren dem Bogen entlang.
private struct ArcEnd: Shape {
    var fraction: Double
    /// Abstand der Bogenmitte vom Rand.
    let inset: CGFloat
    /// Radius des Kreises.
    let radius: CGFloat

    var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let r = min(rect.width, rect.height) / 2 - inset
        let angle = Angle.degrees(arcStart + arcSweep * fraction).radians
        let center = CGPoint(x: rect.midX + r * CGFloat(cos(angle)), y: rect.midY + r * CGFloat(sin(angle)))
        return Path(ellipseIn: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
    }
}

/// Kachel eines Faktors: Name (mit Farbmarke der Phase), Wert, Zusatz (Ziel), Balken der Punkte, Bewertung.
private struct FactorTile: View {
    let factor: FactorScore
    let input: ScoreInput
    let goalMinutes: Int
    @Environment(\.colorScheme) private var scheme

    private var view: (label: String, value: String, extra: String?, stage: SleepStage?) {
        func share(_ percent: Int?) -> String { L("stages_share", Int64(percent ?? 0)) }
        switch factor.factor {
        case .duration:
            return (L("score_duration"), Format.duration(minutes: input.asleepMinutes ?? 0),
                    L("chart_goal", Format.duration(minutes: goalMinutes)), nil)
        case .deep: return (stageName(.deep), share(input.deepPercent), nil, .deep)
        case .rem: return (stageName(.rem), share(input.remPercent), nil, .rem)
        case .awake: return (stageName(.awake), share(input.awakePercent), nil, .awake)
        case .latency: return (L("night_latency"), Format.duration(minutes: input.latencyMinutes ?? 0), nil, nil)
        }
    }

    var body: some View {
        let view = self.view
        let palette = ChartPalette(scheme)
        let band = bandName(factor.band)
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                if let stage = view.stage {
                    RoundedRectangle(cornerRadius: 2).fill(StageColors.color(stage, scheme)).frame(width: 8, height: 8)
                }
                Text(view.label).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(Brand.secondaryText(scheme))
            }
            Text(view.value).font(Fonts.manrope(17, .bold, relativeTo: .headline))
            if let extra = view.extra {
                Text(extra).font(Fonts.manrope(11, relativeTo: .caption2)).foregroundStyle(Brand.secondaryText(scheme))
            }
            // Balken der Punkte (0–100), immer in derselben Farbe; die Farbmarke oben zeigt die Phase.
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.track)
                    Capsule().fill(palette.active).frame(width: max(6, geo.size.width * Double(factor.points) / 100))
                        .opacity(factor.points > 0 ? 1 : 0)
                }
            }
            .frame(height: 6)
            .padding(.top, 8)
            Text(band).font(Fonts.manrope(13, .semibold, relativeTo: .footnote)).padding(.top, 4)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(StageColors.track(scheme).opacity(scheme == .dark ? 0.6 : 0.7), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(([view.label + ": " + view.value, view.extra, band] as [String?]).compactMap { $0 }.joined(separator: ". "))
    }
}

/// Kurzzeile in der Karte des Verlaufs: kleiner Ring mit dem Wert, daneben «Schlafwert» und die Bewertung. Vorläufiger
/// Wert (ohne Schlafphasen): Ring gestrichelt und dünn statt voll.
struct ScoreLine: View {
    let result: SleepScoreResult
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let palette = ChartPalette(scheme)
        let style = result.provisional
            ? StrokeStyle(lineWidth: 2, lineCap: .butt, dash: [3, 2.4])
            : StrokeStyle(lineWidth: 4, lineCap: .round)
        HStack(spacing: 10) {
            ZStack {
                Circle().inset(by: 2).stroke(palette.track, lineWidth: 4)
                Circle().inset(by: 2)
                    .trim(from: 0, to: Double(result.score) / 100)
                    .stroke(palette.active, style: style)
                    .rotationEffect(.degrees(-90))
                Text(result.score.formatted()).font(Fonts.manrope(13, .bold, relativeTo: .footnote))
            }
            .frame(width: 40, height: 40)
            VStack(alignment: .leading, spacing: 0) {
                Text(L("score_title")).font(Fonts.manrope(12, .semibold, relativeTo: .caption)).foregroundStyle(Brand.secondaryText(scheme))
                Text(bandName(result.band)).font(Fonts.manrope(15, .semibold, relativeTo: .subheadline))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(scoreDescription(result))
    }
}
