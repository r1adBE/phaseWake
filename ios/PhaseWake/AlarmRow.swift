import PhaseWakeCore
import SwiftUI

/// Ein Wecker in der Liste der Startseite: Fenster, Tage, Überspringen, Schalter; Tippen öffnet ihn.
struct AlarmRow: View {
    @Environment(PhoneModel.self) private var model
    let alarm: AlarmSpec
    let onTap: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                // Einschlaf-Modus wirkt auf Apple nicht (nur Galaxy Watch): immer das normale Fenster.
                Text(Format.window(alarm))
                    .font(Fonts.manrope(22, .bold, relativeTo: .title2))
                    .foregroundStyle(alarm.enabled ? .primary : .secondary)
                Text(details).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                if let skipped {
                    HStack(spacing: 8) {
                        Text(L("alarm_skipped", Format.shortDay(skipped)))
                            .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                        Button(L("alarm_skip_undo")) { model.setSkipped(alarm, false) }
                            .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                            .buttonStyle(.borderless)
                            .accessibilityLabel(undoLabel(skipped))
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .accessibilityElement(children: .combine) // VoiceOver wie Android: Zeile öffnet, Schalter getrennt.
            .accessibilityAddTraits(.isButton)
            .accessibilityHint(L("editor_edit"))
            .accessibilityAction(.default, onTap)
            .accessibilityActions {
                if let skipped {
                    Button(undoLabel(skipped)) { model.setSkipped(alarm, false) }
                } else if !alarm.isOneShot && alarm.enabled {
                    Button(skipTitle) { model.setSkipped(alarm, true) }
                }
            }
            Toggle(Format.window(alarm), isOn: Binding(get: { alarm.enabled }, set: { model.setEnabled(alarm, $0) }))
                .labelsHidden()
        }
        .contextMenu {
            Button(L("editor_edit"), systemImage: "pencil", action: onTap)
            if skipped != nil {
                Button(L("alarm_skip_undo"), systemImage: "arrow.uturn.backward") { model.setSkipped(alarm, false) }
            } else if !alarm.isOneShot && alarm.enabled {
                Button(skipTitle, systemImage: "forward.end") { model.setSkipped(alarm, true) }
            }
        }
        .card()
    }

    /// Übersprungener Termin «yyyy-MM-dd», solange er bevorsteht.
    private var skipped: String? { AlarmSchedule.skippedDate(alarm, now: Date(), calendar: .gregorianLocal) }

    /// «Do 30.10. überspringen»: der Tag, den der Menüpunkt trifft (nach früh geweckter Nacht der nächste).
    private var skipTitle: String {
        AlarmSchedule.nextSkipDate(alarm, now: Date(), doneUntil: model.doneUntil[alarm.id], calendar: .gregorianLocal,
                                   holidays: model.holidays.dates)
            .map { L("alarm_skip_on", Format.shortDay($0)) } ?? L("alarm_skip")
    }

    /// Für Bildschirmleser: «Überspringen rückgängig: Übersprungen: Do 30.10. (6:30–7:00)».
    private func undoLabel(_ skipped: String) -> String {
        L("alarm_skip_undo_for", L("alarm_skipped", Format.shortDay(skipped)) + " (" + Format.window(alarm) + ")")
    }

    private var details: String {
        var parts = [Format.days(alarm)]
        if !alarm.label.isEmpty { parts.append(alarm.label) }
        return parts.joined(separator: " · ")
    }
}
