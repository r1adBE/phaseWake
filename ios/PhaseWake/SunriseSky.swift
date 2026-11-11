import SwiftUI

/// Farben eines Sonnenaufgangs, Verlauf identisch zu `SunriseSky` (Android).
///
/// Schrift (Audit UI-6, Vollaudit N-3): je Text nach seiner Fläche auf dem Himmel (``ink(_:area:aspect:)``, wie
/// `SunriseSky.ink(area)` auf Android) – hell oder fast schwarz, je nachdem, welche über die Fläche (Ecken, Kanten, Mitte)
/// den grösseren kleinsten Kontrast hat. Dahinter liegt eine halbdurchsichtige Fläche in der Gegenfarbe
/// (``plaque(for:)``, Design 5): Mit ihr erreicht die Schrift überall mindestens 4.5:1, auch dort, wo die Sonne den Himmel
/// unter einer breiten Zeile hell und dunkel zugleich macht. Der gedämpfte Ton nur, wenn auch er das erreicht.
/// ``palette(_:)`` bleibt für die Mitte des unteren Rands (Fortschritt allein).
enum SunriseSky {
    private struct Key { let at: Double; let top: UInt32; let bottom: UInt32; let sun: UInt32 }

    private static let keys = [
        Key(at: 0.00, top: 0x050208, bottom: 0x1E0604, sun: 0x4A1006),
        Key(at: 0.25, top: 0x14071A, bottom: 0x5A1508, sun: 0xA8320C),
        Key(at: 0.50, top: 0x3B1530, bottom: 0xC2410C, sun: 0xFF7A1A),
        Key(at: 0.75, top: 0xD9774A, bottom: 0xFFA25C, sun: 0xFFD08A),
        Key(at: 1.00, top: 0xFFE6C2, bottom: 0xFFF6E8, sun: 0xFFFFFF),
    ]

    /// Mindestkontrast der Schrift (WCAG AA).
    static let minContrast = 4.5
    /// Deckkraft der Fläche hinter der Schrift (Design 5). Gerechnet über alle Fortschritte und Zeilen: ab 0.2 überall
    /// ≥ 4.5:1, mit 0.3 Reserve (≥ 6:1).
    static let plaqueAlpha = 0.3
    /// Seitenverhältnis (Höhe / Breite) eines üblichen iPhones im Hochformat, bis die Fläche gemessen ist.
    static let portrait = 2.17

    /// Farbe als sRGB-Anteile 0–1.
    struct RGB: Equatable {
        let r: Double
        let g: Double
        let b: Double

        init(r: Double, g: Double, b: Double) {
            self.r = r
            self.g = g
            self.b = b
        }

        init(hex: UInt32) {
            self.init(r: Double((hex >> 16) & 0xFF) / 255, g: Double((hex >> 8) & 0xFF) / 255, b: Double(hex & 0xFF) / 255)
        }

        var color: Color { Color(red: r, green: g, blue: b) }
    }

    /// Weisse Schrift; dunkle Schrift (fast schwarz, Nachthimmel); gedämpfte Töne dazu.
    static let light = RGB(hex: 0xFFFFFF)
    static let dark = RGB(hex: 0x050208)
    private static let lightMuted = RGB(hex: 0xFFD9C2)
    private static let darkMuted = RGB(hex: 0x3A1E08)

    /// Alle Farben zu einem Fortschritt, als sRGB (testbar).
    struct Palette: Equatable { let top: RGB; let bottom: RGB; let sun: RGB; let content: RGB; let muted: RGB }

    struct Colors { let top: Color; let bottom: Color; let sun: Color; let content: Color; let muted: Color }

    /// Schrift auf dem Himmel: Haupttext und leiserer Ton.
    struct Ink: Equatable { let content: RGB; let muted: RGB }

    /// Fläche eines Texts als Anteil der Himmelsfläche (0–1, links/oben = 0), wie `SunriseSky.Area` (Android).
    struct Area: Equatable {
        let left: Double
        let top: Double
        let right: Double
        let bottom: Double

        /// Mitte des Himmels (bevor ein Text seine Lage kennt).
        static let center = Area(left: 0.5, top: 0.5, right: 0.5, bottom: 0.5)

        /// Fläche von `rect` in `sky` (beide im selben Koordinatenraum); ohne messbaren Himmel die Mitte.
        static func of(_ rect: CGRect, in sky: CGRect) -> Area {
            guard sky.width > 0, sky.height > 0 else { return .center }
            return Area(left: (rect.minX - sky.minX) / sky.width, top: (rect.minY - sky.minY) / sky.height,
                        right: (rect.maxX - sky.minX) / sky.width, bottom: (rect.maxY - sky.minY) / sky.height)
        }
    }

    static func palette(_ progress: Double) -> Palette {
        let c = gradient(progress)
        let content = contrast(light, c.bottom) >= contrast(dark, c.bottom) ? light : dark
        let tinted = content == light ? lightMuted : darkMuted
        return Palette(top: c.top, bottom: c.bottom, sun: c.sun, content: content,
                       muted: contrast(tinted, c.bottom) >= minContrast ? tinted : content)
    }

    static func colors(_ progress: Double) -> Colors {
        let c = palette(progress)
        return Colors(top: c.top.color, bottom: c.bottom.color, sun: c.sun.color, content: c.content.color, muted: c.muted.color)
    }

    /// Himmel an der Stelle (`x`, `y`) als Anteil der Fläche, `aspect` = Höhe / Breite. Rechnet wie `SunriseBackground`
    /// zeichnet: senkrechter Verlauf, darüber der Schein der Sonne (Mitte 0.5 / 1.18 − 0.5p, Radius in Breiten
    /// 0.45 + 1.05p; Stopps 0, 0.35, 1).
    static func background(_ progress: Double, x: Double, y: Double, aspect: Double) -> RGB {
        let p = min(1, max(0, progress))
        let c = gradient(p)
        let base = mix(c.top, c.bottom, min(1, max(0, y)))
        let d = hypot(x - 0.5, aspect * (y - (1.18 - 0.5 * p))) / (0.45 + 1.05 * p)
        let alpha: Double = if d >= 1 { 0 } else if d <= 0.35 { 1 - 0.45 * d / 0.35 } else { 0.55 * (1 - (d - 0.35) / 0.65) }
        return mix(base, c.sun, alpha)
    }

    /// Hintergründe einer Fläche: Ecken, Kantenmitten und Mitte.
    static func samples(_ progress: Double, area: Area, aspect: Double) -> [RGB] {
        let xs = [area.left, (area.left + area.right) / 2, area.right]
        let ys = [area.top, (area.top + area.bottom) / 2, area.bottom]
        return xs.flatMap { x in ys.map { y in background(progress, x: x, y: y, aspect: aspect) } }
    }

    /// Schrift für einen Text auf `area`: hell oder dunkel, je nach grösserem kleinstem Kontrast über die Fläche. Der
    /// gedämpfte Ton nur, wenn er auf der Fläche samt ``plaque(for:)`` überall ≥ ``minContrast`` erreicht.
    static func ink(_ progress: Double, area: Area = .center, aspect: Double = portrait) -> Ink {
        let backgrounds = samples(progress, area: area, aspect: aspect)
        func worst(_ color: RGB, on list: [RGB]) -> Double { list.map { contrast(color, $0) }.min() ?? 1 }
        let content = worst(light, on: backgrounds) >= worst(dark, on: backgrounds) ? light : dark
        let plaqued = backgrounds.map { behindText($0, content: content) }
        let tinted = content == light ? lightMuted : darkMuted
        return Ink(content: content, muted: worst(tinted, on: plaqued) >= minContrast ? tinted : content)
    }

    /// Fläche hinter der Schrift `content`: die Gegenfarbe mit ``plaqueAlpha``.
    static func plaque(for content: RGB) -> Color {
        (content == light ? dark : light).color.opacity(plaqueAlpha)
    }

    /// Himmel `background` mit der Fläche für die Schrift `content` darüber (zum Prüfen des Kontrasts).
    static func behindText(_ background: RGB, content: RGB) -> RGB {
        mix(background, content == light ? dark : light, plaqueAlpha)
    }

    /// Kontrastverhältnis nach WCAG 2 (1–21).
    static func contrast(_ x: RGB, _ y: RGB) -> Double {
        let lx = luminance(x), ly = luminance(y)
        return (max(lx, ly) + 0.05) / (min(lx, ly) + 0.05)
    }

    /// Relative Leuchtdichte nach WCAG 2.
    static func luminance(_ c: RGB) -> Double {
        func linear(_ v: Double) -> Double { v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
        return 0.2126 * linear(c.r) + 0.7152 * linear(c.g) + 0.0722 * linear(c.b)
    }

    /// Oben, unten und Sonne zum Fortschritt.
    private static func gradient(_ progress: Double) -> (top: RGB, bottom: RGB, sun: RGB) {
        let p = min(1, max(0, progress))
        let i = min(keys.lastIndex { $0.at <= p } ?? 0, keys.count - 2)
        let a = keys[i], b = keys[i + 1]
        let t = min(1, max(0, (p - a.at) / (b.at - a.at)))
        return (mix(RGB(hex: a.top), RGB(hex: b.top), t), mix(RGB(hex: a.bottom), RGB(hex: b.bottom), t),
                mix(RGB(hex: a.sun), RGB(hex: b.sun), t))
    }

    /// Mischung im sRGB-Raum, wie Verläufe gezeichnet werden.
    private static func mix(_ x: RGB, _ y: RGB, _ t: Double) -> RGB {
        RGB(r: x.r + (y.r - x.r) * t, g: x.g + (y.g - x.g) * t, b: x.b + (y.b - x.b) * t)
    }
}
