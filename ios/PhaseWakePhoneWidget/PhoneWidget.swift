import SwiftUI
import WidgetKit

/// iPhone-Widget «Nächster Wecker»: Weckfenster, Tag und Nacht-Check (✓ bzw. ! mit der ersten offenen
/// Prüfung) auf dem Home- und dem Sperrbildschirm (rund, rechteckig, in einer Zeile). Liest den Stand, den die
/// App in der App-Gruppe ablegt (``PhoneWidgetSnapshot``); Antippen öffnet die App. Gleicher Inhalt wie das
/// Android-Widget, auch «Details von heute»: am Weckmorgen bis 12 Uhr auf dem Home-Bildschirm die letzte Nacht.
@main
struct PhaseWakePhoneWidgets: WidgetBundle {
    var body: some Widget { PhoneNextAlarmWidget() }
}

/// Ein Zeitpunkt der Zeitleiste.
struct PhoneAlarmEntry: TimelineEntry {
    let date: Date
    let plan: PhoneWidgetSnapshot.Plan?
    let issue: String?
    /// Am Weckmorgen bis 12 Uhr die letzte Nacht (nur Home-Bildschirm); sonst `nil`.
    var night: PhoneWidgetSnapshot.LastNight? = nil
}

/// Zeitleiste: ein Eintrag jetzt, einer nach jeder kommenden Zielzeit, damit nach dem Wecken von selbst der
/// nächste Wecker erscheint, und einer um 12 Uhr am Weckmorgen (dann endet «Letzte Nacht»).
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
        let noon = snapshot?.morningEnd().map { [$0] } ?? []
        let switches = ((snapshot?.plans ?? []).map { $0.target.addingTimeInterval(60) } + noon).filter { $0 > now }.sorted()
        let entries = [entry(at: now, snapshot: snapshot)] + switches.map { entry(at: $0, snapshot: snapshot) }
        // Spätestens nach 30 Minuten neu lesen (wie Android); die App lädt bei Änderungen ohnehin neu.
        completion(Timeline(entries: entries, policy: .after(now.addingTimeInterval(30 * 60))))
    }

    private func entry(at date: Date, snapshot: PhoneWidgetSnapshot?) -> PhoneAlarmEntry {
        PhoneAlarmEntry(date: date, plan: snapshot?.next(after: date), issue: snapshot?.issue,
                        night: snapshot?.morningNight(at: date))
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
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

/// Farben des Home-Widgets je Design des Systems, identisch zum Android-Widget (`widget_*` in values/colors.xml):
/// dunkel Nachthimmel mit warmem Weiss, hell «Creme Caramel» wie der Tageshimmel (F4E4CC → E9C9A0) mit Tinte 2A2340.
/// Kontrast oben/unten ≥ 4.5:1 (hell 11.9/9.4 bzw. 7.8/6.2, dunkel 15.8/18.6 bzw. 10.0/11.8). Titel: ``title(_:)``.
enum PhoneWidgetPalette {
    /// Oberes Ende des Verlaufs.
    static func top(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.nightTop : Color(hex: 0xF4E4CC) }
    /// Unteres Ende des Verlaufs.
    static func bottom(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.nightBottom : Color(hex: 0xE9C9A0) }
    /// Haupttext (Weckfenster).
    static func text(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.moon : Brand.ink }
    /// Nebentext (Tag, Nacht-Check).
    static func secondary(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.muted : Color(hex: 0x4A4058) }
    /// Titel in der Hauptfarbe: hell das tiefe Lavendel 4436A6 (7.2/5.7:1; 5546C8 hätte unten nur 4.3:1), dunkel
    /// ``Brand/accent(_:)`` (8.5/10.0).
    static func title(_ scheme: ColorScheme) -> Color { scheme == .dark ? Brand.accent(scheme) : Color(hex: 0x4436A6) }
}

/// Hintergrund als Verlauf nach [PhoneWidgetPalette]; die Sperrbildschirm-Grössen zeichnet das System selbst.
private struct PhoneAlarmBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        LinearGradient(colors: [PhoneWidgetPalette.top(scheme), PhoneWidgetPalette.bottom(scheme)], startPoint: .top, endPoint: .bottom)
    }
}
