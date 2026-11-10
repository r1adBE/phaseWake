import PhaseWakeCore
import SwiftUI

/// «Gute Nacht» auf dem Startbildschirm (wie `GoodNightButton` der Galaxy Watch), nur für einen Wecker mit Schlafdauer:
/// «Schlaf 7 h 30 min ab jetzt»; danach die festgelegte Zielzeit, nochmals tippen hebt es auf. Mehr als 16 Std. vor der
/// Weckzeit nur der Hinweis. Siehe `GoodNightState`.
struct GoodNightRow: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        if let alarm = model.sleepAlarm, let minutes = alarm.sleepDurationMinutes {
            let active = model.goodNightActive
            let tooFar = model.goodNightTooFar && !active
            Button { model.toggleGoodNight() } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("home_good_night")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    Text(secondary(active: active, tooFar: tooFar, minutes: minutes))
                        .font(Fonts.manrope(12, relativeTo: .footnote))
                        .foregroundStyle(Brand.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .disabled(tooFar)
            .listRowBackground(RoundedRectangle(cornerRadius: 12).fill(active ? Brand.deep : Brand.echo))
        }
    }

    private func secondary(active: Bool, tooFar: Bool, minutes: Int) -> String {
        if active, let target = model.goodNight?.target { return L("notification_window_text", Format.time(target)) }
        if tooFar { return L("home_good_night_too_far") }
        return L("home_good_night_hint", Format.duration(minutes: minutes))
    }
}

/// «Nacht aufzeichnen» auf dem Startbildschirm (wie `RecordChip` der Galaxy Watch): starten bzw., solange eine
/// Aufzeichnung läuft, «Aufzeichnung stoppen» mit «Seit 23:10». Zu wenig Akku: kurzer Hinweis, nichts startet.
struct RecordRow: View {
    @Environment(WatchModel.self) private var model
    @State private var batteryLow = false

    var body: some View {
        Button {
            if model.recordSince != nil {
                LearnRecorder.shared.stop(.watch)
            } else {
                Task { batteryLow = await LearnRecorder.shared.startManual(by: "watch") == .battery }
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(L(model.recordSince != nil ? "record_stop" : "record_start"))
                    .font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                if let since = model.recordSince {
                    Text(L("record_since", Format.time(since)))
                        .font(Fonts.manrope(12, relativeTo: .footnote))
                        .foregroundStyle(Brand.muted)
                } else if batteryLow {
                    Text(L("record_battery_low"))
                        .font(Fonts.manrope(12, relativeTo: .footnote))
                        .foregroundStyle(Brand.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
