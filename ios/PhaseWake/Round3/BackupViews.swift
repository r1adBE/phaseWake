import PhaseWakeCore
import SwiftUI
import UniformTypeIdentifiers

/// Einstellungen «Sichern» und «Wiederherstellen»: verschlüsselte Datei über die Dialoge des Systems.
struct BackupSection: View {
    @Environment(PhoneModel.self) private var model
    @State private var askPassword = false
    @State private var document: BackupDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var restoreFile: Data?
    @State private var message: String?

    var body: some View {
        Section(L("settings_backup")) {
            Text(L("settings_backup_hint")).font(.footnote)
            Button(L("settings_backup")) { askPassword = true }
            Button(L("settings_restore")) { importing = true }
            if let message {
                Text(message).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .sheet(isPresented: $askPassword) {
            BackupPasswordSheet { data in
                document = BackupDocument(data: data)
                exporting = true
            }
        }
        .fileExporter(isPresented: $exporting, document: document, contentType: .data, defaultFilename: fileName) { result in
            switch result {
            case .success: message = L("backup_done")
            case .failure(let error):
                message = L("backup_failed")
                DiagStore.shared.log(.error, "backup not saved: \(error.localizedDescription)")
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            guard case .success(let url) = result else { return }
            // Datei aus «Dateien»: nur mit Zugriff auf den sicheren Bereich lesbar.
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            do {
                // Grösse zuerst: Eine versehentlich gewählte grosse Datei (Video) wird nicht ganz gelesen.
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard Int64(size) <= BackupFile.maxFileBytes else {
                    message = L("restore_unreadable")
                    DiagStore.shared.log(.error, "backup too large: \(size) bytes")
                    return
                }
                restoreFile = try Data(contentsOf: url)
            } catch {
                // Nicht lesbar (z. B. in iCloud noch nicht geladen, kein Zugriff): kein Passwortfehler.
                message = L("restore_unreadable")
                DiagStore.shared.log(.error, "backup not readable: \(error.localizedDescription)")
            }
        }
        .sheet(item: Binding(get: { restoreFile.map(RestoreFile.init) }, set: { restoreFile = $0?.data })) { file in
            RestoreSheet(file: file.data) { message = L("restore_done") }
        }
    }

    private var fileName: String {
        L("backup_file_name", SleepNotes.dateString(Date(), calendar: .current))
    }
}

/// Datei zum Wiederherstellen als Element für `sheet(item:)`.
private struct RestoreFile: Identifiable {
    let data: Data
    var id: Int { data.hashValue }
}

/// Passwort zweimal (mindestens 8 Zeichen), dann wird verschlüsselt.
private struct BackupPasswordSheet: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let onReady: (Data) -> Void
    @State private var password = ""
    @State private var repeated = ""
    @State private var busy = false
    @State private var failed = false

    private var problem: String? {
        if !BackupFile.passwordOk(password) { return L("backup_password_short") }
        if password != repeated { return L("backup_password_mismatch") }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                SecureField(L("backup_password"), text: $password).textContentType(.newPassword)
                SecureField(L("backup_password_repeat"), text: $repeated).textContentType(.newPassword)
                if let problem, !password.isEmpty { Text(problem).font(.footnote).foregroundStyle(.red) }
                Text(L("backup_password_hint")).font(.footnote).foregroundStyle(.secondary)
                Text(L("backup_health_hint_apple")).font(.footnote).foregroundStyle(.secondary)
                if busy { ProgressView(L("backup_busy")) }
                if failed { Text(L("backup_failed")).font(.footnote) }
            }
            .navigationTitle(L("settings_backup"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("backup_create")) { Task { await create() } }.disabled(problem != nil || busy)
                }
            }
        }
    }

    private func create() async {
        busy = true
        defer { busy = false }
        do {
            let data = try await BackupIO.encrypt(BackupIO.payload(model), password: password)
            dismiss()
            onReady(data)
        } catch {
            failed = true
            DiagStore.shared.log(.error, "backup failed")
        }
    }
}

/// Passwort, Vorschau, Rückfrage und Gerätesperre; erst dann werden die Daten ersetzt.
private struct RestoreSheet: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let file: Data
    let onDone: () -> Void
    @State private var password = ""
    @State private var payload: BackupPayload?
    @State private var wrong = false
    @State private var busy = false
    @State private var confirm = false

    var body: some View {
        NavigationStack {
            Form {
                SecureField(L("backup_password"), text: $password).textContentType(.password)
                if wrong { Text(L("restore_wrong_password")).font(.footnote).foregroundStyle(.red) }
                if busy { ProgressView(L("backup_busy")) }
                if let payload {
                    let c = BackupIO.counts(payload)
                    Text(L("restore_preview", Int64(c.alarms), Int64(c.nights), Int64(c.noteDays)))
                    Text(L("backup_health_hint_apple")).font(.footnote).foregroundStyle(.secondary)
                    Button(L("restore_confirm"), role: .destructive) { confirm = true }
                }
            }
            .navigationTitle(L("settings_restore"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("restore_open")) { Task { await open() } }.disabled(password.isEmpty || busy || payload != nil)
                }
            }
            .confirmationDialog(L("restore_confirm_title"), isPresented: $confirm, titleVisibility: .visible) {
                Button(L("restore_confirm"), role: .destructive) { Task { await replace() } }
            } message: {
                Text(L("restore_confirm_text"))
            }
        }
    }

    private func open() async {
        busy = true
        defer { busy = false }
        do {
            payload = try await BackupIO.decrypt(file, password: password)
            wrong = false
        } catch {
            wrong = true
        }
    }

    private func replace() async {
        guard let payload, await DeviceAuth.confirm(reason: L("auth_restore")) else { return }
        BackupIO.apply(payload, to: model)
        dismiss()
        onDone()
    }
}
