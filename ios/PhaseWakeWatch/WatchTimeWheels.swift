import SwiftUI

/// Uhrzeit-Räder im Editor der Uhr (Vollaudit N-10): folgen der 12/24-Std.-Einstellung (bei 12 Std. ein drittes Rad
/// für AM/PM, Texte des Systems) und haben für VoiceOver je einen eigenen Namen (Stunden, Minuten – Einheitennamen des
/// Systems, keine eigenen Texte) samt der ganzen Uhrzeit als Wert. Gespeichert wird immer 0–23.
struct WatchTimeWheels: View {
    @Binding var hour: Int
    @Binding var minute: Int
    /// 12 Std. mit AM/PM (aus der Einstellung des Systems, ``WheelClock/usesTwelveHours(_:)``).
    var twelveHours = WheelClock.usesTwelveHours()

    var body: some View {
        let value = Format.time(hour: hour, minute: minute)
        HStack(spacing: 2) {
            if twelveHours {
                Picker(WheelClock.unitName(.hours), selection: Binding(get: { WheelClock.hour12(hour) },
                                                                      set: { hour = WheelClock.hour24(hour12: $0, pm: hour >= 12) })) {
                    ForEach(1...12, id: \.self) { Text(Format.twoDigits($0)).tag($0) }
                }
                .accessibilityValue(value)
            } else {
                Picker(WheelClock.unitName(.hours), selection: $hour) {
                    ForEach(0..<24, id: \.self) { Text(Format.twoDigits($0)).tag($0) }
                }
                .accessibilityValue(value)
            }
            Text(":").font(Fonts.manrope(20, .bold, relativeTo: .title3)).accessibilityHidden(true)
            Picker(WheelClock.unitName(.minutes), selection: $minute) {
                ForEach(0..<60, id: \.self) { Text(Format.twoDigits($0)).tag($0) }
            }
            .accessibilityValue(value)
            if twelveHours {
                let calendar = Calendar.current
                Picker(calendar.amSymbol + "/" + calendar.pmSymbol,
                       selection: Binding(get: { hour >= 12 }, set: { hour = WheelClock.hour24(hour12: WheelClock.hour12(hour), pm: $0) })) {
                    Text(calendar.amSymbol).tag(false)
                    Text(calendar.pmSymbol).tag(true)
                }
                .accessibilityValue(value)
            }
        }
    }
}

/// Umrechnung der Räder zwischen 12 und 24 Std. (testbar).
enum WheelClock {
    /// Ob die Uhrzeit in `locale` mit AM/PM erscheint (Vorlage «j» des Systems, folgt der Einstellung 12/24 Std.).
    static func usesTwelveHours(_ locale: Locale = .current) -> Bool {
        (DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale) ?? "").contains("a")
    }

    /// Stunde 0–23 als 1–12.
    static func hour12(_ hour: Int) -> Int {
        let h = ((hour % 12) + 12) % 12
        return h == 0 ? 12 : h
    }

    /// Stunde 1–12 mit AM/PM als 0–23 (12 AM = 0, 12 PM = 12).
    static func hour24(hour12: Int, pm: Bool) -> Int {
        (hour12 % 12) + (pm ? 12 : 0)
    }

    /// Name der Einheit in der Sprache des Systems («Stunden», «Minuten»), als Name des Rads für VoiceOver.
    static func unitName(_ unit: UnitDuration) -> String {
        let formatter = MeasurementFormatter()
        formatter.unitStyle = .long
        return formatter.string(from: unit)
    }
}
