import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Schrift auf dem Sonnenaufgang, iPhone: Kontrast über den ganzen Verlauf und je Textfläche samt Fläche dahinter.
final class SunriseInkPhoneTests: XCTestCase {
    func testSunriseTextKeepsContrastOverTheWholeProgress() {
        for step in 0...1000 {
            let p = Double(step) / 1000
            let c = SunriseSky.palette(p)
            XCTAssertGreaterThanOrEqual(SunriseSky.contrast(c.content, c.bottom), SunriseSky.minContrast, "content at \(p)")
            XCTAssertGreaterThanOrEqual(SunriseSky.contrast(c.muted, c.bottom), SunriseSky.minContrast, "muted at \(p)")
        }
    }

    func testSunriseSwitchesToDarkTextBeforeTheOldThreshold() {
        // Früher erst ab 0.635 dunkel (dort nur rund 2.5:1 für die gedämpfte Zeile): jetzt deutlich früher.
        XCTAssertEqual(SunriseSky.palette(0).content, SunriseSky.light)
        XCTAssertEqual(SunriseSky.palette(0.6).content, SunriseSky.dark)
        XCTAssertEqual(SunriseSky.palette(1).content, SunriseSky.dark)
        let first = (0...1000).first { SunriseSky.palette(Double($0) / 1000).content == SunriseSky.dark }
        XCTAssertNotNil(first)
        XCTAssertLessThan(Double(first ?? 1000) / 1000, 0.6)
    }

    func testContrastFormula() {
        XCTAssertEqual(SunriseSky.contrast(SunriseSky.RGB(hex: 0xFFFFFF), SunriseSky.RGB(hex: 0x000000)), 21, accuracy: 0.01)
        XCTAssertEqual(SunriseSky.contrast(SunriseSky.RGB(hex: 0x777777), SunriseSky.RGB(hex: 0x777777)), 1, accuracy: 0.0001)
    }

    func testInkKeepsContrastOnEveryTextAreaWithPlaque() {
        // Ausdrücklich typisiert und in Schritten gebaut: sonst braucht der Swift-Compiler zu lange für die Typen.
        var areas: [SunriseSky.Area] = []
        let widths: [Double] = [0.3, 0.6, 0.9]
        for row in 0...19 {
            let top: Double = Double(row) * 0.05
            let bottom: Double = min(1.0, top + 0.08)
            for width in widths {
                let left: Double = (1.0 - width) / 2.0
                let right: Double = (1.0 + width) / 2.0
                areas.append(SunriseSky.Area(left: left, top: top, right: right, bottom: bottom))
            }
        }
        for step in 0...50 {
            let p = Double(step) / 50
            let aspects: [Double] = [1.8, SunriseSky.portrait, 2.2]
            for aspect in aspects {
                for area in areas {
                    let ink = SunriseSky.ink(p, area: area, aspect: aspect)
                    for background in SunriseSky.samples(p, area: area, aspect: aspect) {
                        let behind = SunriseSky.behindText(background, content: ink.content)
                        XCTAssertGreaterThanOrEqual(SunriseSky.contrast(ink.content, behind), SunriseSky.minContrast, "\(p) \(area)")
                        XCTAssertGreaterThanOrEqual(SunriseSky.contrast(ink.muted, behind), SunriseSky.minContrast, "muted \(p) \(area)")
                    }
                }
            }
        }
    }

    func testInkFollowsTheTextArea() {
        // Mitten im Sonnenaufgang: oben noch dunkler Himmel (helle Schrift), unten über der Sonne dunkle Schrift.
        let top = SunriseSky.Area(left: 0.2, top: 0.05, right: 0.8, bottom: 0.12)
        let bottom = SunriseSky.Area(left: 0.3, top: 0.88, right: 0.7, bottom: 0.95)
        XCTAssertEqual(SunriseSky.ink(0.6, area: top).content, SunriseSky.light)
        XCTAssertEqual(SunriseSky.ink(0.6, area: bottom).content, SunriseSky.dark)
        XCTAssertEqual(SunriseSky.Area.of(CGRect(x: 10, y: 20, width: 50, height: 10), in: CGRect(x: 0, y: 0, width: 100, height: 200)),
                       SunriseSky.Area(left: 0.1, top: 0.1, right: 0.6, bottom: 0.15))
        XCTAssertEqual(SunriseSky.Area.of(.zero, in: .zero), .center)
    }
}
