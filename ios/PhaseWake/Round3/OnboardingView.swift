import PhaseWakeCore
import SwiftUI

/// Ersteinrichtung: Willkommen → Uhr verbinden → Rechte → Erster Wecker → Probewecker → Fertig. Jeder
/// Schritt lässt sich überspringen; «Einrichtung wiederholen» in den Einstellungen zeigt sie wieder.
struct OnboardingView: View {
    /// Merker «Einrichtung erledigt» (bestehende Installationen mit Weckern gelten als eingerichtet).
    static let doneKey = "onboardingDone"
    private static let steps = 5

    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @AppStorage(OnboardingView.doneKey) private var done = false
    @State private var step = 0
    @State private var failed: [PhoneCheck] = []
    @State private var time = Calendar.current.date(from: DateComponents(hour: 7, minute: 0)) ?? Date()
    @State private var window = Double(AlarmSpec.defaultWindowMinutes)
    @State private var created: AlarmSpec?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if step < Self.steps {
                        Text(L("onb_step", Int64(step + 1), Int64(Self.steps)))
                            .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                            .foregroundStyle(Brand.secondaryText(scheme))
                    }
                    content
                }
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .safeAreaInset(edge: .bottom) { navigation }
            .toolbar {
                if step < Self.steps {
                    ToolbarItem(placement: .cancellationAction) { Button(L("onb_skip")) { done = true } }
                }
            }
            .task(id: step) { failed = await NightCheck.collect() }
        }
        .interactiveDismissDisabled()
    }

    @ViewBuilder private var content: some View {
        switch step {
        case 0:
            LogoMark().frame(width: 72, height: 72)
            title("onb_welcome_title")
            Text(L("onb_welcome_text"))
            Label(L("onb_privacy"), systemImage: "lock").foregroundStyle(Brand.secondaryText(scheme))
        case 1:
            title("onb_watch_title")
            watchStatus
        case 2:
            title("onb_rights_title")
            rights
        case 3:
            title("onb_alarm_title")
            firstAlarm
        case 4:
            title("test_title")
            TestAlarmButton()
            TransientStatusCard()
        default:
            title("onb_done_title")
            Text(L("onb_done_text"))
        }
    }

    private func title(_ key: String) -> some View {
        Text(L(key)).font(Fonts.manrope(26, .bold, relativeTo: .title)).accessibilityAddTraits(.isHeader)
    }

    /// Stand der Uhr wie im Nacht-Check: gekoppelt, App installiert.
    @ViewBuilder private var watchStatus: some View {
        if failed.contains(.watch) {
            Label(PhoneCheck.watch.problem, systemImage: "xmark.circle")
        } else if failed.contains(.watchApp) {
            Label(PhoneCheck.watchApp.problem, systemImage: "xmark.circle")
        } else {
            Label(L("onb_watch_ok"), systemImage: "checkmark.circle")
        }
    }

    /// Mitteilungen und Wecker (je mit Knopf), Apple Health freiwillig.
    @ViewBuilder private var rights: some View {
        ForEach([PhoneCheck.notifications, .alarms], id: \.self) { check in
            HStack {
                Text(check.title)
                Spacer()
                if failed.contains(check) {
                    Button(L("check_allow")) { Task { await NightCheck.fix(check); failed = await NightCheck.collect() } }
                        .buttonStyle(.borderedProminent)
                } else {
                    Label(L("onb_granted"), systemImage: "checkmark")
                }
            }
        }
        HStack {
            Text(L("onb_optional", L("health_connect")))
            Spacer()
            Button(L("check_allow")) { Task { _ = await model.health.requestAccess() } }
        }
    }

    /// Zielzeit und Fenster; legt einen Wecker für die Werktage an (wie der Editor).
    @ViewBuilder private var firstAlarm: some View {
        Text(L("onb_alarm_text"))
        DatePicker("", selection: $time, displayedComponents: .hourAndMinute)
            .datePickerStyle(.wheel)
            .labelsHidden()
            .frame(maxWidth: .infinity)
        Text(L("editor_window", Int(window)))
        Slider(value: $window, in: Double(AlarmSpec.minWindowMinutes)...Double(AlarmSpec.maxWindowMinutes), step: 5)
        if let created {
            Label(L("onb_alarm_done", Format.window(created)), systemImage: "checkmark.circle")
        } else {
            Button(L("onb_alarm_create")) { createAlarm() }
                .buttonStyle(.borderedProminent)
                .disabled(!model.canAddAlarm)
        }
    }

    private var navigation: some View {
        HStack {
            if step > 0 {
                Button(L("onb_back")) { step -= 1 }
            }
            Spacer()
            if step < Self.steps {
                Button(L("onb_next")) { step += 1 }.buttonStyle(.borderedProminent)
            } else {
                Button(L("onb_finish")) { done = true }.buttonStyle(.borderedProminent)
            }
        }
        .padding(16)
        .background(.bar)
    }

    private func createAlarm() {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        guard let spec = try? AlarmSpec(id: UUID().uuidString, hour: c.hour ?? 7, minute: c.minute ?? 0,
                                        days: [.monday, .tuesday, .wednesday, .thursday, .friday], windowMinutes: Int(window)) else { return }
        model.upsert(spec)
        created = spec
    }
}
