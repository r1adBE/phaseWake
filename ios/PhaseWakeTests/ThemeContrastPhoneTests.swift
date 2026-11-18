import PhaseWakeCore
import XCTest
@testable import PhaseWake

/// Helles Design «Creme Caramel», wie `SkyPageContrastTest` (Android): Phasenfarben und Chip-Rand sind Nicht-Text und
/// brauchen ≥ 3:1 auf Karte, Creme und (Chip-Rand) dem Tageshimmel jeder Stimmung.
final class ThemeContrastPhoneTests: XCTestCase {
    private let card: UInt32 = 0xFFFCF7

    private func rgb(_ hex: UInt32) -> SunriseSky.RGB { SunriseSky.RGB(hex: hex) }

    /// `fg` mit Deckkraft `alpha` über `bg`, im sRGB-Raum wie beim Zeichnen.
    private func over(_ fg: UInt32, _ alpha: Double, on bg: SunriseSky.RGB) -> SunriseSky.RGB {
        let f = rgb(fg)
        return SunriseSky.RGB(r: bg.r + (f.r - bg.r) * alpha, g: bg.g + (f.g - bg.g) * alpha,
                              b: bg.b + (f.b - bg.b) * alpha)
    }

    /// Tageshimmel: oben (Tag und tiefe Nacht), unten, unten mit vollem Abend- bzw. Morgenschein am Horizont und darauf
    /// dem Sonnenschein (ungünstig gerechnet wie `daySkyAt` auf Android).
    private var daySkies: [SunriseSky.RGB] {
        let bottom = rgb(DaySkyColors.bottom)
        let dusk = over(DaySkyColors.duskLow, DaySkyColors.duskAlpha, on: bottom)
        let dawn = over(DaySkyColors.dawnLow, DaySkyColors.dawnAlpha, on: bottom)
        return [rgb(DaySkyColors.top), rgb(DaySkyColors.nightTop), bottom, dusk, dawn,
                over(DaySkyColors.duskHigh, DaySkyColors.duskSun, on: dusk),
                over(DaySkyColors.dawnHigh, DaySkyColors.dawnSun, on: dawn)]
    }

    func testStageColorsVisibleButNotForText() throws {
        for stage in [SleepStage.awake, .rem, .light, .deep] {
            let hex = try XCTUnwrap(StageColors.hex(stage, dark: false))
            for ground in [card, 0xFBF3E6] {
                XCTAssertGreaterThanOrEqual(SunriseSky.contrast(rgb(hex), rgb(ground)), 3, "\(stage) auf \(ground)")
            }
        }
        // Als Schrift zu hell: darum stehen Legenden im Nebentext.
        let light = try XCTUnwrap(StageColors.hex(.light, dark: false))
        XCTAssertLessThan(SunriseSky.contrast(rgb(light), rgb(card)), SunriseSky.minContrast)
        XCTAssertNil(StageColors.hex(.unknown, dark: false))
    }

    func testChipBorderVisibleOnCardsAndDaySky() {
        let border = Brand.chipBorderLight
        for ground in [rgb(card), rgb(0xFBF3E6)] + daySkies {
            let chip = over(border.hex, border.opacity, on: ground)
            XCTAssertGreaterThanOrEqual(SunriseSky.contrast(chip, ground), 3, "Chiprand auf \(ground)")
        }
    }
}
