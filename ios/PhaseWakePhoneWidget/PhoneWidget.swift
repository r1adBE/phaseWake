import SwiftUI
import WidgetKit

/// iPhone-Widget «Nächster Wecker»: Weckfenster, Tag und Nacht-Check (✓ bzw. ! mit der ersten offenen
/// Prüfung) auf dem Home- und dem Sperrbildschirm. Liest den Stand, den die App in der App-Gruppe ablegt
/// (``PhoneWidgetSnapshot``); Antippen öffnet die App. Gleicher Inhalt wie das Android-Widget.
@main
struct PhaseWakePhoneWidgets: WidgetBundle {
    var body: some Widget { PhoneNextAlarmWidget() }
}

/// Ein Zeitpunkt der Zeitleiste.
struct PhoneAlarmEntry: TimelineEntry {
    let date: Date
    let plan: PhoneWidgetSnapshot.Plan?
    let issue: String?
}

/// Zeitleiste: ein Eintrag jetzt und einer nach jeder kommenden Zielzeit, damit nach dem Wecken von
/// selbst der nächste Wecker erscheint.
struct PhoneAlarmProvider: TimelineProvider {
    func placeholder(in context: Context) -> PhoneAlarmEntry {
        let target = Calendar.current.date(bySettingHour: 7, minute: 0, second: 0, of: Date()) ?? Date()
        return PhoneAlarmEntry(date: Date(), plan: .init(windowStart: target.addingTimeInterval(-1800), target: target, label: ""), issue: "")
    }

    func getSnapshot(in context: Context, completion: @escaping (PhoneAlarmEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : entry(at: Date(), snapshot: PhoneWidgetSnapshot.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<PhoneAlarmEntry>) -> Void) {
        let now = Date()
        let snapshot = PhoneWidgetSnapshot.load()
        let switches = (snapshot?.plans ?? []).map { $0.target.addingTimeInterval(60) }.filter { $0 > now }
        let entries = [entry(at: now, snapshot: snapshot)] + switches.map { entry(at: $0, snapshot: snapshot) }
        // Spätestens nach 30 Minuten neu lesen (wie Android); die App lädt bei Änderungen ohnehin neu.
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(30 * 60))))
    }

    private func entry(at date: Date, snapshot: PhoneWidgetSnapshot?) -> PhoneAlarmEntry {
        PhoneAlarmEntry(date: date, plan: snapshot?.next(after: date), issue: snapshot?.issue)
    }
}

/// Widget-Beschreibung mit den unterstützten Grössen.
struct PhoneNextAlarmWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: PhoneWidgetSnapshot.kind, provider: PhoneAlarmProvider()) { entry in
            PhoneAlarmView(entry: entry)
                .containerBackground(for: .widget) { PhoneAlarmBackground() }
        }
        .configurationDisplayName(L("widget_name"))
        .description(L("widget_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular, .accessoryInline])
    }
}

/// Hintergrund im Mitternacht-Verlauf, im hellen Design Papier.
private struct PhoneAlarmBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        if scheme == .dark {
            LinearGradient(colors: [Brand.nightTop, Brand.nightBottom], startPoint: .top, endPoint: .bottom)
        } else {
            Brand.paper
        }
    }
}
