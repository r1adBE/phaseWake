import PhaseWakeCore
import SwiftUI

// MARK: - Abendroutine

extension PhoneModel {
    var canAddRoutineItem: Bool { routineItems.count < Routine.maxItems }

    func addRoutineItem(_ text: String) {
        let clean = Routine.cleanText(text)
        guard canAddRoutineItem, !clean.isEmpty else { return }
        setRoutineItems(routineItems + [RoutineItem(id: Routine.customId(UUID().uuidString.lowercased()), text: clean)])
    }

    func removeRoutineItem(_ id: String) { setRoutineItems(routineItems.filter { $0.id != id }) }

    private func setRoutineItems(_ items: [RoutineItem]) {
        let text = Routine.encodeItems(items)
        privateNotes.setRoutineItemsText(text)
        routineItems = Routine.decodeItems(text)
    }

    func routineDone(night: String) -> [String] { routineTicks[night] ?? [] }

    func toggleRoutine(_ id: String, night: String) {
        routineTicks = Routine.toggle(routineTicks, date: night, id: id)
        privateNotes.setRoutineTicks(routineTicks)
    }

    /// Anzeigename: Vorschläge übersetzt, eigene mit ihrem Text.
    func routineName(_ item: RoutineItem) -> String {
        switch item.id {
        case Routine.phone: L("routine_item_phone")
        case Routine.light: L("routine_item_light")
        case Routine.water: L("routine_item_water")
        default: item.text
        }
    }
}
