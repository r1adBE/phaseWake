import SwiftUI
import WidgetKit

/// Inhalt des iPhone-Widgets je Grösse. Der Nacht-Check zeigt Zeichen und Text, nie nur Farbe.
struct PhoneAlarmView: View {
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme
    let entry: PhoneAlarmEntry

    private var window: String {
        guard let plan = entry.plan else { return L("widget_no_alarm") }
        return Format.window(start: plan.windowStart, target: plan.target, exact: plan.exact == true)
    }

    /// «Morgen · Arbeit»
    private var dayLine: String? {
        guard let plan = entry.plan else { return nil }
        let day = Format.day(plan.target, todayKey: "home_next_today", tomorrowKey: "home_next_tomorrow")
        return [day, plan.label].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// «✓ Bereit für die Nacht» oder «! » mit der ersten offenen Prüfung; ohne Wecker oder Check nichts.
    private var checkLine: String? {
        guard entry.plan != nil, let issue = entry.issue else { return nil }
        return issue.isEmpty ? "✓ " + L("check_ready") : "! " + issue
    }

    var body: some View {
        switch family {
        case .accessoryInline:
            Text(entry.plan == nil ? L("widget_no_alarm") : "☾ " + window)
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label(dayLine ?? "Phase Wake", systemImage: "moon.stars.fill")
                    .font(.system(size: 13, weight: .semibold))
                    .widgetAccentable()
                Text(window).font(.system(size: 17, weight: .bold, design: .rounded)).minimumScaleFactor(0.7)
                if let checkLine { Text(checkLine).font(.system(size: 12)).lineLimit(1) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            home
        }
    }

    /// Home-Bildschirm (klein und mittel).
    private var home: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(L("widget_name"), systemImage: "moon.stars.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Brand.accent(scheme))
            Spacer(minLength: 0)
            Text(window)
                .font(.system(size: family == .systemSmall ? 22 : 28, weight: .bold, design: .rounded))
                .minimumScaleFactor(0.6)
                .lineLimit(1)
                .foregroundStyle(scheme == .dark ? Brand.moon : Color(hex: 0x1A1830))
            if let dayLine {
                Text(dayLine).font(.system(size: 13)).foregroundStyle(Brand.secondaryText(scheme)).lineLimit(1)
            }
            if let checkLine {
                Text(checkLine).font(.system(size: 12, weight: .medium)).foregroundStyle(Brand.secondaryText(scheme)).lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
