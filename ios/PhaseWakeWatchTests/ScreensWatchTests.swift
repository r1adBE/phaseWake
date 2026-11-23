import PhaseWakeCore
import SwiftUI
import UIKit
import WatchKit
import XCTest
@testable import PhaseWakeWatch

/// Bildschirmfotos der Apple Watch (Audit F8): jeder wichtige Bildschirm als PNG, ohne Gerät prüfbar, ob alle Texte
/// erscheinen. Gerendert mit `ImageRenderer` in Bildschirmgrösse der Uhr; die Bilder hängen als Anhang
/// (`.keepAlways`) im Ergebnisbündel, die CI exportiert sie ins Artefakt `apple-bildschirme`.
///
/// Sprache: Die App lädt Texte über `L`/`LP` aus `Bundle.main` (nicht aus `\.locale`); darum wählt die CI die Sprache
/// des Testwirts mit `-testLanguage` (Deutsch, Französisch). Der Dateiname nennt die Sprache, in der er lief.
/// Die Tests prüfen nichts (ausser dass das Rendern nicht abstürzt); ob jeder Schlüssel einen Text hat, prüft
/// `CatalogKeysWatchTests`. Wecker und Zustand der Nacht werden nur für das Bild gesetzt und danach zurückgestellt.
@MainActor
final class ScreensWatchTests: XCTestCase {
    /// Sprache des Testwirts, z. B. «de» oder «fr».
    private var language: String { Bundle.main.preferredLocalizations.first ?? "xx" }

    /// Beispielwecker: 07:00 jeden Tag, Fenster 20 Min; mit `sleep` im Einschlaf-Modus (7½ Std., «Gute Nacht»).
    private func sampleAlarm(sleep: Bool) throws -> AlarmSpec {
        try AlarmSpec(id: sleep ? "screens-sleep" : "screens-alarm", hour: 7, minute: 0,
                      days: Set(Weekday.allCases), windowMinutes: 20, sleepDurationMinutes: sleep ? 450 : nil)
    }

    /// Heute um `hour`:`minute` Ortszeit (die Uhrzeit im Bild hängt nicht von der Zone des Simulators ab).
    private func today(_ hour: Int, _ minute: Int) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: Date()) ?? Date()
    }

    /// Rendert `content` in Bildschirmgrösse auf Schwarz und hängt es als «watch-<sprache>-<name>.png» an.
    private func shoot<V: View>(_ name: String, @ViewBuilder _ content: () -> V) {
        let device = WKInterfaceDevice.current()
        let size = device.screenBounds.size
        let view = ZStack {
            Color.black
            content()
        }
        .frame(width: size.width, height: size.height)
        .environment(WatchModel.shared)
        .tint(Brand.wave)
        let renderer = ImageRenderer(content: view)
        renderer.scale = device.screenScale
        renderer.proposedSize = ProposedViewSize(size)
        let file = "watch-\(language)-\(name)"
        guard let image = renderer.cgImage, let png = UIImage(cgImage: image).pngData() else {
            let note = XCTAttachment(string: "\(file): nicht gerendert")
            note.lifetime = .keepAlways
            add(note)
            return
        }
        let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
        attachment.name = file
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Führt `body` mit den Weckern `alarms` aus und stellt danach die gespeicherten wieder her (auch den Plan).
    private func withAlarms(_ alarms: [AlarmSpec], _ body: () -> Void) {
        let model = WatchModel.shared
        let before = model.storage.snapshot().alarms
        model.storage.mutate({ _ in alarms })
        model.refresh()
        defer {
            model.storage.mutate({ _ in before })
            model.refresh()
            model.reschedule(recorder: false)
        }
        body()
    }

    /// Führt `body` im Zustand `phase` der Nacht aus und stellt danach den bisherigen wieder her.
    private func withPhase(_ phase: SmartAlarm.Phase, _ body: () -> Void) {
        let before = SmartAlarm.shared.phase
        SmartAlarm.shared.phase = phase
        defer { SmartAlarm.shared.phase = before }
        body()
    }

    func testStart() throws {
        let alarm = try sampleAlarm(sleep: false)
        withAlarms([alarm]) { shoot("01-start") { HomeView() } }
        withAlarms([]) { shoot("01-start-leer") { HomeView() } }
    }

    func testEditAlarm() throws {
        let alarm = try sampleAlarm(sleep: false)
        shoot("02-wecker-bearbeiten") { WatchEditorView(start: alarm, isNew: false) }
    }

    func testRingWithEveryReason() {
        for reason in WakeReason.allCases {
            withPhase(.ringing(reason, snoozesLeft: 2)) {
                shoot("03-wecken-\(reason.rawValue.lowercased())") { RingView() }
            }
        }
    }

    func testSnoozed() {
        withPhase(.snoozed(until: today(7, 5), snoozesLeft: 1)) { shoot("04-schlummern") { RingView() } }
    }

    func testGoodNight() throws {
        let alarm = try sampleAlarm(sleep: true)
        withAlarms([alarm]) {
            shoot("05-gute-nacht") { HomeView() }
            // Die Zeilen der Startseite auch einzeln: zeigt der Renderer die Liste nicht, bleiben die Texte sichtbar.
            shoot("05-gute-nacht-zeilen") {
                VStack(alignment: .leading, spacing: 8) {
                    GoodNightRow()
                    WatchNapRow(choosing: .constant(false))
                    RecordRow()
                }
                .padding(.horizontal, 8)
            }
        }
    }

    func testPowerNap() {
        shoot("06-powernap") { NavigationStack { WatchNapView() } }
    }

    func testDiagnostics() {
        shoot("07-diagnose") { NavigationStack { WatchDiagView(unlocked: .constant(true)) } }
    }

    /// Befinden nach dem Wecken (``WatchFeelingView``): Frage, 1 «Erschöpft» … 5 «Erholt», «Später».
    func testFeeling() {
        let ask = WatchFeeling.Ask(alarmId: "screens-alarm", windowStart: today(6, 40), askedAt: Date())
        shoot("08-befinden") { WatchFeelingView(ask: ask) }
    }
}
