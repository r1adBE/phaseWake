import PhaseWakeCore
import SwiftUI

/// Startseite am Abend: die Abendroutine zum Abhaken (gilt für die kommende Nacht). Ohne Punkte nichts.
struct RoutineCard: View {
    @Environment(PhoneModel.self) private var model
    let night: String

    var body: some View {
        if !model.routineItems.isEmpty {
            let done = Set(model.routineDone(night: night))
            VStack(alignment: .leading, spacing: 4) {
                Label(L("routine_title"), systemImage: "checklist").font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                    .padding(.bottom, 4)
                ForEach(model.routineItems) { item in
                    let checked = done.contains(item.id)
                    // Die ganze Zeile hakt ab.
                    Button { model.toggleRoutine(item.id, night: night) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: checked ? "checkmark.circle.fill" : "circle")
                                .font(.title3)
                                .foregroundStyle(checked ? Color.accentColor : .secondary)
                            Text(model.routineName(item)).font(Fonts.manrope(15, relativeTo: .body))
                            Spacer()
                        }
                        .contentShape(Rectangle())
                        .padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(checked ? .isSelected : [])
                }
            }
            .card()
        }
    }
}

/// Einstellungen: Punkte der Abendroutine hinzufügen und entfernen (höchstens 8).
struct RoutineSection: View {
    @Environment(PhoneModel.self) private var model
    @State private var adding = false
    @State private var newText = ""

    var body: some View {
        Section(L("routine_title")) {
            ForEach(model.routineItems) { item in
                Text(model.routineName(item))
            }
            .onDelete { offsets in
                let ids = offsets.map { model.routineItems[$0].id }
                for id in ids { model.removeRoutineItem(id) }
            }
            if model.canAddRoutineItem {
                Button(L("routine_add")) { newText = ""; adding = true }
            }
        }
        .alert(L("routine_add"), isPresented: $adding) {
            TextField(L("editor_label"), text: $newText)
                .onChange(of: newText) { _, value in
                    if value.count > Routine.maxTextLength { newText = String(value.prefix(Routine.maxTextLength)) }
                }
            Button(L("routine_add")) { model.addRoutineItem(newText) }
            Button(L("action_cancel"), role: .cancel) {}
        }
    }
}

/// Gewohnheiten für die Trends-Karte, wie `habitLines` (Android): Regelmässigkeit und Serien nur mit
/// Daten aus Apple Health, die Abendroutine ab 3 Abenden.
@MainActor
func habitLines(model: PhoneModel, sleeps: [SleepSummary], healthConnected: Bool, now: Date = Date()) -> [String] {
    let calendar = Calendar.current
    let today = SleepNotes.nightDate(now, calendar: calendar)
    var lines: [String] = []
    if healthConnected {
        let regular = Regularity.count(Regularity.bedtimes(of: sleeps, calendar: calendar), calendar: calendar)
        if regular.total > 0 {
            lines.append(LP("habit_regular", regular.total, regular.regular, regular.total))
        }
        let reached = Streak.reachedByNight(sleeps, calendar: calendar, goalMinutes: model.sleepGoal)
        let streak = Streak.current(reached, today: today)
        let best = Streak.longest(reached, today: today)
        if streak > 0 { lines.append(LP("habit_streak", streak, streak)) }
        if best > 0 { lines.append(LP("habit_streak_best", best, best)) }
    }
    if let average = Routine.average(model.routineTicks, items: model.routineItems, today: today) {
        lines.append(L("routine_avg", decimalText(average), model.routineItems.count))
    }
    return lines
}

/// Verlauf am Sonntag und Montag: Rückblick auf die zuletzt abgeschlossene Woche.
struct WeeklyCard: View {
    @Environment(PhoneModel.self) private var model
    let summary: WeeklySummary

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(L("weekly_title"), systemImage: "calendar").font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            Text(weeklyText(summary)).font(Fonts.manrope(15, relativeTo: .body))
            if let note = weeklyNoteLine(summary, model: model) {
                Text(note).font(Fonts.manrope(15, relativeTo: .body))
            }
        }
        .card()
    }
}
