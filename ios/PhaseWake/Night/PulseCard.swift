import PhaseWakeCore
import SwiftUI

/// Pulsverlauf (``SleepOverviews/pulse(_:start:)``): Linie je 5 Min (Lücken unterbrochen), Fläche darunter leise
/// ausblendend, Mittel gestrichelt, Beginn und Ende als Uhrzeit. Aus einer Aufzeichnung der Uhr über die ganze Nacht
/// (`fullNight`), sonst im Weckfenster (Untertitel). Wie `PulseCard` (Android).
struct PulseCard: View {
    let curve: PulseCurve
    var fullNight = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let average = L("pulse_average", Int64(curve.average))
        let from = Format.time(curve.start)
        let to = Format.time(curve.end)
        let window: String? = fullNight ? nil : L("chart_window")
        VStack(alignment: .leading, spacing: 4) {
            Text(L("pulse_title")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            if let window {
                Text(window).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(Brand.secondaryText(scheme))
            }
            PulseLine(curve: curve, line: StageColors.color(.awake, scheme), mean: Brand.accent(scheme))
                .frame(height: 104)
                .padding(.top, 4)
                .chartFadeIn()
            HStack {
                Text(from)
                Spacer()
                Text(to)
            }
            .font(Fonts.manrope(11, .semibold, relativeTo: .caption2))
            .foregroundStyle(Brand.secondaryText(scheme))
            ChartLegend(items: [(symbol: AnyView(DashSymbol(color: Brand.accent(scheme))), text: average)])
        }
        .card()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([L("pulse_title"), window, average, from + " – " + to].compactMap { $0 }.joined(separator: ". "))
    }
}

/// Bewegung über die ganze Nacht aus einer Aufzeichnung der Uhr: je 5 Min (``PulseCurve/bucketEpochs``) die grösste
/// Bewegung als Säule, ohne Werte eine flache Spur; Beginn und Ende als Uhrzeit. Wie `MotionCard` (Android).
struct MotionCard: View {
    let series: NightSeries
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let from = Format.time(series.start)
        let to = Format.time(series.start.addingTimeInterval(TimeInterval(series.epochs.count * DetectorConfig.epochSeconds)))
        let buckets: [Double?] = stride(from: 0, to: series.epochs.count, by: PulseCurve.bucketEpochs).map { i in
            series.epochs[i..<min(i + PulseCurve.bucketEpochs, series.epochs.count)].compactMap(\.motion).max()
        }
        VStack(alignment: .leading, spacing: 4) {
            Text(L("motion_title")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            MotionBars(buckets: buckets, active: Brand.accent(scheme), track: StageColors.track(scheme))
                .frame(height: 64)
                .padding(.top, 4)
                .chartFadeIn()
            HStack {
                Text(from)
                Spacer()
                Text(to)
            }
            .font(Fonts.manrope(11, .semibold, relativeTo: .caption2))
            .foregroundStyle(Brand.secondaryText(scheme))
        }
        .card()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L("motion_title") + ". " + from + " – " + to)
    }
}

/// Die Säulen der Bewegung: Höhe relativ zur grössten Bewegung, mindestens 2 pt; ohne Werte in der Farbe der Spur.
private struct MotionBars: View {
    let buckets: [Double?]
    let active: Color
    let track: Color

    var body: some View {
        Canvas { context, size in
            let n = max(1, buckets.count)
            let gap: CGFloat = n > 60 ? 1 : 2
            let width = max(1, (size.width - gap * CGFloat(n - 1)) / CGFloat(n))
            let top = max(buckets.compactMap { $0 }.max() ?? 0.01, 0.01)
            let minimum: CGFloat = 2
            for (i, motion) in buckets.enumerated() {
                let share = CGFloat(min(max((motion ?? 0) / top, 0), 1))
                let height = motion == nil ? minimum : minimum + (size.height - minimum) * share
                let rect = CGRect(x: CGFloat(i) * (width + gap), y: size.height - height, width: width, height: height)
                context.fill(Path(roundedRect: rect, cornerRadius: width / 2), with: .color(motion == nil ? track : active))
            }
        }
    }
}

/// Die Zeichnung des Pulsverlaufs.
private struct PulseLine: View {
    let curve: PulseCurve
    let line: Color
    let mean: Color

    var body: some View {
        Canvas { context, size in
            let lo = (curve.low).rounded(.down) - 3
            let hi = (curve.high).rounded(.up) + 3
            let n = curve.buckets.count
            func x(_ i: Int) -> CGFloat { n <= 1 ? size.width / 2 : size.width * CGFloat(i) / CGFloat(n - 1) }
            func y(_ v: Double) -> CGFloat { size.height * CGFloat(1 - (v - lo) / (hi - lo)) }
            // Zusammenhängende Stücke ohne Lücke.
            var runs: [[(Int, Double)]] = []
            var current: [(Int, Double)] = []
            for (i, v) in curve.buckets.enumerated() {
                if let v {
                    current.append((i, v))
                } else if !current.isEmpty {
                    runs.append(current)
                    current = []
                }
            }
            if !current.isEmpty { runs.append(current) }
            let shade = Gradient(colors: [line.opacity(0.28), line.opacity(0)])
            for run in runs {
                guard let first = run.first, let last = run.last else { continue }
                if run.count == 1 {
                    // Einzelnes Stück zwischen Lücken als Punkt, damit es sichtbar bleibt.
                    context.fill(Path(ellipseIn: CGRect(x: x(first.0) - 2, y: y(first.1) - 2, width: 4, height: 4)), with: .color(line))
                    continue
                }
                var path = Path()
                path.move(to: CGPoint(x: x(first.0), y: y(first.1)))
                for (i, v) in run.dropFirst() { path.addLine(to: CGPoint(x: x(i), y: y(v))) }
                var area = path
                area.addLine(to: CGPoint(x: x(last.0), y: size.height))
                area.addLine(to: CGPoint(x: x(first.0), y: size.height))
                area.closeSubpath()
                context.fill(area, with: .linearGradient(shade, startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
                context.stroke(path, with: .color(line), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
            var avg = Path()
            let avgY = y(Double(curve.average))
            avg.move(to: CGPoint(x: 0, y: avgY))
            avg.addLine(to: CGPoint(x: size.width, y: avgY))
            context.stroke(avg, with: .color(mean), style: StrokeStyle(lineWidth: 1.5, dash: [4, 3]))
        }
    }
}
