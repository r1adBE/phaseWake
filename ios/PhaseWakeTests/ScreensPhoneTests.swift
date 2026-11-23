import PhaseWakeCore
import SwiftUI
import UIKit
import XCTest
@testable import PhaseWake

/// Bildschirmfotos der iPhone-App (Audit F8): Start, Editor, Verlauf, Nachtdetail, Einstellungen und Einrichtung je
/// hell und dunkel als PNG, ohne Gerät prüfbar. Die Bilder hängen als Anhang (`.keepAlways`) im Ergebnisbündel; die
/// CI exportiert sie ins Artefakt `apple-bildschirme`.
///
/// Gezeichnet wird in einem eigenen Fenster über der App (`drawHierarchy`), nicht mit `ImageRenderer`: Dieser zeichnet
/// Listen, Formulare und Scroll-Ansichten (UIKit) nur als Platzhalter. Sprache wie der Testwirt (CI: `-testLanguage`),
/// sie steht im Dateinamen. Die Tests prüfen nichts ausser dass das Zeichnen nicht abstürzt.
@MainActor
final class ScreensPhoneTests: XCTestCase {
    /// Sprache des Testwirts, z. B. «de».
    private var language: String { Bundle.main.preferredLocalizations.first ?? "xx" }

    /// Fester Zeitpunkt am 29.10.2026 in der Zone des Simulators (Uhrzeiten im Bild bleiben gleich).
    private func at(day: Int = 29, _ hour: Int, _ minute: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: 2026, month: 10, day: day, hour: hour, minute: minute))
            ?? Date(timeIntervalSince1970: 1_793_000_000)
    }

    /// Beispielwecker 07:00 werktags, Fenster 20 Min.
    private func sampleAlarm() throws -> AlarmSpec {
        try AlarmSpec(id: "screens-alarm", hour: 7, minute: 0,
                      days: [.monday, .tuesday, .wednesday, .thursday, .friday], windowMinutes: 20)
    }

    /// Beispielnacht: Fenster 06:40–07:00, um 06:52 im leichten Schlaf geweckt, Bewegung und Puls je 30 s.
    private func sampleNight() -> NightRecord {
        let start = at(6, 40)
        let epochs = (0..<40).map { i in
            Epoch(motion: 0.01 + 0.004 * Double(i % 7), heartRate: 52 + Double(i % 5))
        }
        return NightRecord(alarmId: "screens-alarm", windowStart: start, target: at(7, 0), wokeAt: at(6, 52),
                           reason: .lightSleep, snoozes: 1, epochs: epochs, feeling: 4,
                           bedAt: at(day: 28, 23, 5), asleepAt: at(day: 28, 23, 20))
    }

    /// Schlafphasen der Beispielnacht von 23:20 bis 06:52 (Minuten je Phase).
    private func sampleStages() -> [StageSpan] {
        let plan: [(SleepStage, Int)] = [(.awake, 10), (.light, 35), (.deep, 45), (.light, 25), (.rem, 20),
                                         (.light, 40), (.deep, 30), (.light, 30), (.rem, 30), (.awake, 5),
                                         (.light, 45), (.rem, 35), (.light, 40), (.rem, 22), (.light, 40)]
        var start = at(day: 28, 23, 20)
        return plan.map { stage, minutes in
            let end = start.addingTimeInterval(Double(minutes) * 60)
            defer { start = end }
            return StageSpan(start: start, end: end, stage: stage)
        }
    }

    /// Zeichnet `view` hell und dunkel und hängt «iphone-<sprache>-<name>-hell/dunkel.png» an.
    private func shoot<V: View>(_ name: String, _ view: V) {
        for dark in [false, true] {
            let file = "iphone-\(language)-\(name)-\(dark ? "dunkel" : "hell")"
            guard let png = render(view, dark: dark) else {
                let note = XCTAttachment(string: "\(file): nicht gezeichnet (kein Fenster)")
                note.lifetime = .keepAlways
                add(note)
                continue
            }
            let attachment = XCTAttachment(data: png, uniformTypeIdentifier: "public.png")
            attachment.name = file
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    /// `view` in einem eigenen Fenster der Szene des Testwirts als PNG; `nil` ohne Szene.
    private func render<V: View>(_ view: V, dark: Bool) -> Data? {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            return nil
        }
        let window = UIWindow(windowScene: scene)
        window.frame = scene.screen.bounds
        window.windowLevel = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 1)
        window.overrideUserInterfaceStyle = dark ? .dark : .light
        let root = view
            .environment(PhoneModel.shared)
            .tint(Brand.accent(dark ? .dark : .light))
        window.rootViewController = UIHostingController(rootView: root)
        window.isHidden = false
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }
        // SwiftUI Zeit zum Aufbauen geben (onAppear, Laden, Layout).
        RunLoop.main.run(until: Date().addingTimeInterval(1))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            _ = window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        return image.pngData()
    }

    /// Führt `body` mit den Nächten `nights` im Modell aus und stellt danach die bisherigen wieder her.
    private func withNights(_ nights: [NightRecord], _ body: () -> Void) {
        let model = PhoneModel.shared
        let before = model.nights
        model.nights = nights
        defer { model.nights = before }
        body()
    }

    func testStart() {
        shoot("01-start", HomeView())
    }

    func testEditor() throws {
        let alarm = try sampleAlarm()
        shoot("02-editor", EditorView(existing: alarm))
    }

    func testHistory() {
        withNights([sampleNight()]) { shoot("03-verlauf", HistoryView()) }
    }

    func testNightDetail() {
        let night = sampleNight()
        let item = HistoryItem(kind: .phaseWake, date: "2026-10-29", night: night)
        let page = NightDetailPage(data: NightDetailData(item: item, stages: sampleStages()), sourceName: nil,
                                   scoreSource: nil)
        shoot("04-nachtdetail", NavigationStack { page })
    }

    func testSettings() {
        shoot("05-einstellungen", SettingsView())
    }

    func testOnboarding() {
        shoot("06-einrichtung", OnboardingView())
    }
}
