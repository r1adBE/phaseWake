import Foundation
import Observation
import PhaseWakeCore
import SwiftUI

/// Befinden nach dem Wecken auf der Apple Watch (``FeelingAsk``, Entscheid 23.11.2026, gleich wie die Galaxy Watch,
/// `FeelingPrompt`): Der Abschluss der Nacht stellt die Frage nach einem Stopp auf der Uhr (``ask(_:now:)``, in
/// `SmartAlarm.finish`, nachdem die Nacht gespeichert und ans iPhone geschickt ist); die Startansicht zeigt danach
/// ``WatchFeelingView`` statt der Startseite. Der Stopp selbst läuft vollständig wie ohne Abfrage. Nur im Speicher:
/// Endet die App, entfällt die Frage, und die Startseite des iPhones fragt wie bisher.
@MainActor
@Observable
final class WatchFeeling {
    static let shared = WatchFeeling()

    /// Gefragte Nacht (Wecker und Fensterbeginn, wie im Verlauf) und Zeitpunkt der Frage.
    struct Ask: Equatable {
        let alarmId: String
        let windowStart: Date
        let askedAt: Date
    }

    /// Offene Frage; `nil` = keine. Gilt nur ``FeelingAsk/seconds`` lang (``current(now:)``).
    private(set) var open: Ask?

    /// Fragt nach dem Befinden zur Nacht `night`, gestellt um `now`; ersetzt eine ältere Frage.
    func ask(_ night: NightRecord, now: Date = Date()) {
        open = Ask(alarmId: night.alarmId, windowStart: night.windowStart, askedAt: now)
    }

    /// Offene Frage um `now`; `nil` ohne Frage oder nach ``FeelingAsk/seconds`` (``FeelingAsk/isOpen(askedAt:now:)``).
    func current(now: Date = Date()) -> Ask? {
        guard let open, FeelingAsk.isOpen(askedAt: open.askedAt, now: now) else { return nil }
        return open
    }

    /// Schliesst die Frage `ask` (gewählt, «Später», abgelaufen); eine inzwischen neuere bleibt offen.
    func close(_ ask: Ask) {
        if open == ask { open = nil }
    }

    /// Speichert die Wahl `feeling` (1–5) zur Nacht von `ask` im Verlauf der Uhr (`lastNight`, sonst der des
    /// ``WatchModel``) und schickt die so geänderte Nacht ans iPhone (`send`, sonst
    /// ``WatchConnectivityBridge/send(night:)``: `transferUserInfo`, in Reihenfolge nach der Nacht ohne Befinden,
    /// zugestellt, sobald das iPhone erreichbar ist). Das iPhone übernimmt sie nur ohne eigenes Befinden
    /// (``FeelingAsk/merged(stored:incoming:)``). Im Protokoll nur, ob es klappte, nie der Wert.
    /// - Returns: ob gespeichert und geschickt.
    @discardableResult
    func choose(_ feeling: Int, for ask: Ask, lastNight: WatchLastNight? = nil,
                send: ((NightRecord) -> Void)? = nil) -> Bool {
        close(ask)
        let store = lastNight ?? WatchModel.shared.lastNight
        guard let night = store.setFeeling(feeling, alarmId: ask.alarmId, windowStart: ask.windowStart) else {
            DiagStore.shared.log(.sync, "feeling not saved, night missing")
            return false
        }
        if let send { send(night) } else { WatchConnectivityBridge.shared.send(night: night) }
        DiagStore.shared.log(.sync, "feeling from watch queued")
        return true
    }
}

/// Textschlüssel des Befindens 1–5 auf der Uhr, wie am iPhone und auf Wear OS («Erschöpft» … «Erholt»).
func watchFeelingKey(_ value: Int) -> String {
    switch value {
    case 1: "feeling_1"
    case 2: "feeling_2"
    case 3: "feeling_3"
    case 4: "feeling_4"
    default: "feeling_5"
    }
}

/// «Wie fühlst du dich?» direkt nach dem Stopp: fünf grosse Knöpfe 1–5 untereinander (fünf Ziele à 44 pt passen auf
/// der Uhr nicht nebeneinander), die Enden beschriftet wie am iPhone («Erschöpft», «Erholt»), darunter «Später». Still
/// (kein Ton, keine Vibration); ohne Eingabe schliesst sie sich ``FeelingAsk/seconds`` nach dem Stopp.
struct WatchFeelingView: View {
    let ask: WatchFeeling.Ask
    /// Mindesthöhe jedes Knopfs: Tippziel nach Apple-Vorgabe (44 pt) mit Reserve wie der Schlummer-Knopf.
    static let minHeight: CGFloat = 48

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                Text(L("feeling_question"))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                ForEach(NightRecord.feelingRange, id: \.self) { value in
                    Button { WatchFeeling.shared.choose(value, for: ask) } label: { label(value) }
                        .accessibilityLabel(L("feeling_rate", Int64(value), L(watchFeelingKey(value))))
                }
                Button { WatchFeeling.shared.close(ask) } label: {
                    Text(L("feeling_later"))
                        .font(Fonts.manrope(13, relativeTo: .footnote))
                        .frame(maxWidth: .infinity, minHeight: Self.minHeight)
                }
                .tint(Brand.deep)
            }
            .padding(.horizontal, 4)
        }
        .background(NightSky(strength: NightSky.watchPage))
        // Ohne Eingabe still schliessen; auch nach einer Pause der App (der Rest ist dann schon abgelaufen). Wirft
        // `sleep`, wurde die Ansicht ersetzt (z. B. durch ein neues Klingeln): Dann ist die Frage ebenfalls vorbei.
        .task(id: ask.askedAt) {
            let left = ask.askedAt.addingTimeInterval(FeelingAsk.seconds).timeIntervalSinceNow
            if left > 0 { try? await Task.sleep(for: .seconds(left)) }
            WatchFeeling.shared.close(ask)
        }
    }

    /// Ziffer in der Schrift der Sprache, an den Enden mit Beschriftung darunter.
    private func label(_ value: Int) -> some View {
        VStack(spacing: 0) {
            Text(value.formatted()).font(Fonts.manrope(17, .bold, relativeTo: .headline))
            if value == NightRecord.feelingRange.lowerBound || value == NightRecord.feelingRange.upperBound {
                Text(L(watchFeelingKey(value)))
                    .font(Fonts.manrope(11, relativeTo: .caption2))
                    .foregroundStyle(Brand.muted)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, minHeight: Self.minHeight)
    }
}
