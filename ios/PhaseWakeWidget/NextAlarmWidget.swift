import PhaseWakeCore
import SwiftUI
import WidgetKit

/// Komplikation: nächstes Weckfenster auf dem Zifferblatt; Mond in Lavendel (auf getönten Zifferblättern färbt das System). Liest die Wecker aus der gemeinsamen
/// App-Gruppe der Uhr-App; die Uhr-App lädt die Komplikation bei jeder Änderung neu.
@main
struct PhaseWakeWidgets: WidgetBundle {
    var body: some Widget { NextAlarmWidget() }
}

struct NextAlarmEntry: TimelineEntry {
    let date: Date
    let plan: WakePlan?
}

struct NextAlarmProvider: TimelineProvider {
    func placeholder(in context: Context) -> NextAlarmEntry {
        let target = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
        return NextAlarmEntry(date: Date(), plan: WakePlan(alarmId: "", windowStart: target.addingTimeInterval(-1800), target: target))
    }

    func getSnapshot(in context: Context, completion: @escaping (NextAlarmEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : entry(at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<NextAlarmEntry>) -> Void) {
        let now = Date()
        let current = entry(at: now)
        // Nach der Zielzeit gilt der nächste Wecker: dann neu berechnen.
        let refresh = current.plan?.target.addingTimeInterval(60) ?? now.addingTimeInterval(6 * 3600)
        completion(Timeline(entries: [current], policy: .after(refresh)))
    }

    private func entry(at date: Date) -> NextAlarmEntry {
        let storage = PhaseWakeStorage.watchShared
        let plan = AlarmSchedule.earliestPlan(storage.snapshot().alarms, now: date, doneUntil: storage.doneUntil, calendar: .current,
                                              holidays: storage.holidays.dates)
        return NextAlarmEntry(date: date, plan: plan)
    }
}

struct NextAlarmWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "NextAlarm", provider: NextAlarmProvider()) { entry in
            NextAlarmView(entry: entry)
                .containerBackground(for: .widget) { Color.clear }
        }
        .configurationDisplayName("Phase Wake")
        .description(L("tile_light_sleep"))
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

struct NextAlarmView: View {
    @Environment(\.widgetFamily) private var family
    let entry: NextAlarmEntry

    private var window: String {
        guard let plan = entry.plan else { return "–" }
        return Format.time(plan.windowStart) + "–" + Format.time(plan.target)
    }

    var body: some View {
        switch family {
        case .accessoryCircular:
            VStack(spacing: 0) {
                Image(systemName: "moon.stars.fill").font(.system(size: 14)).foregroundStyle(Brand.wave)
                Text(entry.plan.map { Format.time($0.target) } ?? "–")
                    .font(.system(size: 13, weight: .semibold, design: .rounded))
                    .minimumScaleFactor(0.6)
            }
            .widgetAccentable()
            .accessibilityLabel(entry.plan == nil ? L("home_no_alarm") : L("tile_next") + " " + window)
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label("Phase Wake", systemImage: "moon.stars.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Brand.wave)
                    .widgetAccentable()
                Text(entry.plan == nil ? L("home_no_alarm") : window)
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.7)
                if entry.plan != nil {
                    Text(L("tile_light_sleep")).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .accessoryCorner:
            Image(systemName: "moon.stars.fill")
                .font(.system(size: 20))
                .foregroundStyle(Brand.wave)
                .widgetAccentable()
                .widgetLabel(entry.plan.map { Format.time($0.target) } ?? "–")
        default:
            Text(entry.plan == nil ? L("home_no_alarm") : "☾ " + window)
        }
    }
}
