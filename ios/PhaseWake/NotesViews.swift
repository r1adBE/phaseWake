import PhaseWakeCore
import SwiftUI

/// Chips, die umbrechen, wenn die Zeile voll ist.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let width = rows.map(\.width).max() ?? 0
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, rows.count - 1))
        let proposed = proposal.width.flatMap { $0.isFinite ? $0 : nil }
        return CGSize(width: proposed ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = rows[rows.count - 1].indices.isEmpty ? size.width : rows[rows.count - 1].width + spacing + size.width
            if needed > width && !rows[rows.count - 1].indices.isEmpty {
                rows.append(Row())
            }
            var row = rows[rows.count - 1]
            row.width = row.indices.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
            rows[rows.count - 1] = row
        }
        return rows.filter { !$0.indices.isEmpty }
    }
}

private struct NoteOption: Identifiable {
    let id: String
    let name: String
}

/// Alle wählbaren Notizen einer Nacht als Schalter-Chips; jeder Tipp wird sofort gespeichert.
struct NoteChips: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityDifferentiateWithoutColor) private var withoutColor
    let nightDate: String

    var body: some View {
        let selected = Set(model.selectedNotes(nightDate))
        let options = SleepNote.allCases.map { NoteOption(id: $0.rawValue, name: L($0.textKey)) }
            + model.customNotes.map { NoteOption(id: $0.id, name: $0.name) }
        FlowLayout {
            ForEach(options) { option in
                let on = selected.contains(option.id)
                Button { model.toggleNote(option.id, nightDate: nightDate) } label: {
                    HStack(spacing: 4) {
                        // «Differenzieren ohne Farbe»: gewählt zusätzlich mit Haken.
                        if on && withoutColor { Image(systemName: "checkmark").font(.caption.bold()).accessibilityHidden(true) }
                        Text(option.name)
                    }
                        .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
                        .lineLimit(1)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        // Farbwelt B: Rand Lavendel, gewählt gefüllt statt umrandet; Ecken 12 wie auf Android.
                        .foregroundStyle(on ? Color.primary : Brand.secondaryText(scheme))
                        .background(on ? Brand.chipSelected(scheme) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(on ? Color.clear : Brand.chipBorder(scheme), lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }
}

/// Startseite, abends: «Notizen für heute Nacht».
struct TonightNotesCard: View {
    let nightDate: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(L("notes_title"), systemImage: "note.text").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
            NoteChips(nightDate: nightDate)
        }
        .card()
    }
}

/// Notizen einer vergangenen Nacht: zeigt die gesetzten, ein Tipp öffnet die Auswahl.
struct NightNotesButton: View {
    @Environment(PhoneModel.self) private var model
    let nightDate: String
    /// Titel der Auswahl, etwa das Datum der Nacht.
    let title: String
    @State private var editing = false

    var body: some View {
        let names = model.selectedNotes(nightDate).compactMap(model.noteName)
        Button { editing = true } label: {
            HStack(spacing: 6) {
                Image(systemName: names.isEmpty ? "note.text.badge.plus" : "note.text")
                if !names.isEmpty {
                    Text(names.joined(separator: " · ")).multilineTextAlignment(.leading)
                }
            }
            .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
            .foregroundStyle(.tint)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(([L("notes_label")] + names).joined(separator: ", "))
        .sheet(isPresented: $editing) {
            NavigationStack {
                ScrollView {
                    NoteChips(nightDate: nightDate).padding()
                }
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
            }
            .presentationDetents([.medium, .large])
            .environment(model)
        }
    }
}

/// Verlauf: «Was deinen Schlaf beeinflusst».
struct NoteEffectsCard: View {
    @Environment(PhoneModel.self) private var model
    let effects: [NoteEffect]

    var body: some View {
        // Gelöschte eigene Notizen zeigen nichts.
        let known = effects.filter { model.noteName($0.noteId) != nil }
        let visible = known.filter(\.isVisible)
        VStack(alignment: .leading, spacing: 6) {
            Text(L("notes_effects_title")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
            if !visible.isEmpty {
                ForEach(visible, id: \.noteId) { effect in
                    Text(LP("notes_effect_line", effect.nightsWithout, model.noteName(effect.noteId) ?? "", signed(effect.delta), effect.nightsWith, effect.nightsWithout))
                        .font(Fonts.manrope(15, relativeTo: .body))
                }
            } else if !known.isEmpty {
                Text(L("notes_effect_none")).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(.secondary)
            } else {
                Text(L("notes_effect_hint")).font(Fonts.manrope(13, relativeTo: .footnote)).foregroundStyle(.secondary)
            }
        }
        .card()
    }

    /// «−0,8» / «+0,6» mit einer Nachkommastelle.
    private func signed(_ value: Double) -> String {
        let text = abs(value).formatted(.number.precision(.fractionLength(1)))
        return (value < 0 ? "\u{2212}" : "+") + text
    }
}

/// Einstellungen: eigene Notizen hinzufügen, umbenennen, löschen.
struct CustomNotesSection: View {
    @Environment(PhoneModel.self) private var model
    @State private var adding = false
    @State private var newName = ""

    var body: some View {
        Section {
            ForEach(model.customNotes) { note in
                CustomNoteRow(note: note)
            }
            .onDelete { offsets in
                for index in offsets { model.deleteCustomNote(model.customNotes[index].id) }
            }
            Button(L("notes_custom_add")) { newName = ""; adding = true }
                .disabled(!model.canAddCustomNote)
        } header: {
            Text(L("notes_custom_title"))
        } footer: {
            Text(L("notes_custom_hint", SleepNotes.maxCustom))
        }
        .alert(L("notes_custom_add"), isPresented: $adding) {
            TextField("", text: $newName)
                .onChange(of: newName) { _, value in
                    if value.count > SleepNotes.maxNameLength { newName = String(value.prefix(SleepNotes.maxNameLength)) }
                }
            Button(L("notes_custom_add")) { model.addCustomNote(newName) }
            Button(L("action_cancel"), role: .cancel) {}
        }
    }
}

/// Eine eigene Notiz; Umbenennen direkt im Feld, gespeichert beim Bestätigen oder Verlassen.
private struct CustomNoteRow: View {
    @Environment(PhoneModel.self) private var model
    let note: CustomNote
    @State private var name = ""
    @FocusState private var focused: Bool

    var body: some View {
        TextField("", text: $name)
            .focused($focused)
            .submitLabel(.done)
            .onAppear { name = note.name }
            .onChange(of: name) { _, value in
                if value.count > SleepNotes.maxNameLength { name = String(value.prefix(SleepNotes.maxNameLength)) }
            }
            .onChange(of: focused) { _, isFocused in if !isFocused { save() } }
            .onSubmit(save)
    }

    private func save() {
        if SleepNotes.cleanName(name).isEmpty { name = note.name } else { model.renameCustomNote(note.id, name) }
    }
}
