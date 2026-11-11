import PhaseWakeCore
import SwiftUI

/// Ersteinrichtung: Willkommen → Uhr verbinden → Rechte → Erster Wecker → Lernphase → Probewecker → Fertig. Jeder
/// Schritt lässt sich überspringen; «Einrichtung wiederholen» in den Einstellungen zeigt sie wieder.
struct OnboardingView: View {
    /// Merker «Einrichtung erledigt» (bestehende Installationen mit Weckern gelten als eingerichtet).
    static let doneKey = "onboardingDone"
    private static let steps = 6
    /// Schritt der Lernphase: statt «Weiter» die Knöpfe «Später» und «Lernphase starten».
    private static let learningStep = 4
    /// Schritt «Erster Wecker».
    static let alarmStep = 3

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
        case Self.alarmStep:
            title("onb_alarm_title")
            firstAlarm
        case Self.learningStep:
            title("learn_consent_title")
            LearningOnboardingStep()
        case 5:
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
            Label(PhoneCheck.watch.problem, systemImage: "exclamationmark.triangle")
        } else if failed.contains(.watchApp) {
            Label(PhoneCheck.watchApp.problem, systemImage: "exclamationmark.triangle")
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
        // Wie im Editor: «Genau · 10 · 20 · 30».
        let exact = Int(window) == AlarmSpec.exactWindow
        Text(exact ? L("editor_window_exact") : L("editor_window", Int(window)))
        Picker(L("chart_window"), selection: $window) {
            ForEach([AlarmSpec.exactWindow, 10, 20, AlarmSpec.maxWindowMinutes], id: \.self) { minutes in
                Text(minutes == AlarmSpec.exactWindow ? L("editor_window_exact") : L("duration_minutes", Int64(minutes)))
                    .tag(Double(minutes))
            }
        }
        .pickerStyle(.segmented)
        // Grosse Segmente: Tippziel nahe 44 pt.
        .controlSize(.large)
        if exact {
            Text(L("editor_window_exact_hint")).font(.footnote).foregroundStyle(.secondary)
        }
        if let created {
            Label(L("onb_alarm_done", Format.window(created)), systemImage: "checkmark.circle")
        } else {
            Button(L("onb_alarm_create")) { createAlarm() }
                .buttonStyle(.borderedProminent)
                .disabled(!model.canAddAlarm)
        }
    }

    private var navigation: some View {
        // Lernphase noch nicht gestartet: nur «Später» und «Lernphase starten», beide gehen weiter (Audit U-2).
        let askLearning = step == Self.learningStep && PhoneLearning.shared.state.consent != true
        return VStack(spacing: 8) {
            if askLearning {
                Button { PhoneLearning.shared.setConsent(true); step += 1 } label: {
                    Text(L("learn_consent_start")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
            HStack {
                if step > 0 {
                    Button(L("onb_back")) { step -= 1 }
                }
                Spacer()
                if askLearning {
                    Button(L("learn_consent_later")) { PhoneLearning.shared.setConsent(false); step += 1 }
                } else if step < Self.steps {
                    Button(L("onb_next")) { next() }.buttonStyle(.borderedProminent)
                } else {
                    Button(L("onb_finish")) { done = true }.buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(16)
        .background(.bar)
    }

    /// «Weiter»: Auf dem Schritt «Erster Wecker» legt es den gezeigten Wecker an, falls noch keiner angelegt ist (wie
    /// «Wecker anlegen», Audit UI-5) – wer nur «Weiter» tippt, hätte sonst keinen Wecker.
    private func next() {
        if Self.createsOnNext(step: step, created: created != nil, canAdd: model.canAddAlarm) { createAlarm() }
        step += 1
    }

    /// Ob «Weiter» auf `step` den gezeigten Wecker anlegt: nur auf dem Schritt «Erster Wecker», wenn noch keiner
    /// angelegt ist und noch einer Platz hat.
    static func createsOnNext(step: Int, created: Bool, canAdd: Bool) -> Bool {
        step == alarmStep && !created && canAdd
    }

    private func createAlarm() {
        let c = Calendar.current.dateComponents([.hour, .minute], from: time)
        guard let spec = try? AlarmSpec(id: UUID().uuidString, hour: c.hour ?? 7, minute: c.minute ?? 0,
                                        days: [.monday, .tuesday, .wednesday, .thursday, .friday], windowMinutes: Int(window)) else { return }
        model.upsert(spec)
        created = spec
    }
}
