import AVFoundation
import Observation
import PhaseWakeCore
import SwiftUI

extension SoundType {
    /// Textschlüssel wie Android («sound_type_snore» …).
    var textKey: String { "sound_type_" + rawValue.lowercased() }

    var symbol: String {
        switch self {
        case .snore: "zzz"
        case .talk: "bubble.left"
        case .other: "waveform"
        }
    }
}

/// «Schnarchen: 12 Min (8 Ausschnitte) · Sprechen: 2 Ausschnitte»; `nil` ohne Schnarchen und Sprechen. Wie Android.
func soundSummaryText(_ clips: [SoundClip]) -> String? {
    let summary = SoundEvents.summary(clips)
    var parts: [String] = []
    if summary.snoreClips > 0 {
        parts.append(LP("sound_summary", summary.snoreClips, Format.duration(minutes: summary.snoreMinutes), summary.snoreClips))
    }
    if summary.talkClips > 0 {
        parts.append(LP("sound_summary_talk", summary.talkClips, summary.talkClips))
    }
    return parts.isEmpty ? nil : parts.joined(separator: " · ")
}

/// Startseite: «Geräusche aufnehmen» für die kommende Nacht, mit kurzer Erklärung.
struct SoundRecordingCard: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.openURL) private var openURL
    let night: String

    var body: some View {
        let recorder = model.recorder
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: Binding(
                // Zeigt, was wirklich läuft; die Vorgabe «an» startet die Aufnahme abends von selbst.
                get: { recorder.running },
                set: { on in Task { await model.setSoundRec(on, night: night) } }
            )) {
                Label(L("sound_rec_title"), systemImage: "mic").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
            }
            Text(hint(recorder)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            if recorder.permissionDenied && !recorder.running {
                Button(L("dnd_action")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .font(Fonts.manrope(13, .semibold, relativeTo: .footnote))
            }
        }
        .card()
    }

    private func hint(_ recorder: SoundRecorder) -> String {
        if recorder.permissionDenied && !recorder.running { return L("sound_rec_permission") }
        if recorder.running { return L(recorder.charging ? "sound_rec_running" : "sound_rec_plug_in") }
        return L("sound_rec_hint")
    }
}

/// Morgenbericht und Verlauf: Bilanz der Geräusche einer Nacht; ein Tipp öffnet die Ausschnitte.
/// Ohne Ausschnitte nichts.
struct NightSoundsButton: View {
    @Environment(PhoneModel.self) private var model
    let night: String
    let title: String
    @State private var open = false

    var body: some View {
        let clips = model.recorder.nightClips(night)
        if !clips.isEmpty {
            Button { open = true } label: {
                Label(soundSummaryText(clips) ?? L("sound_clips_title"), systemImage: "waveform")
                    .font(Fonts.manrope(12, .semibold, relativeTo: .caption))
                    .foregroundStyle(.tint)
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
            .sheet(isPresented: $open) {
                NightSoundsSheet(night: night, title: title).environment(model)
            }
        }
    }
}

/// «Geräusche» einer Nacht: Zeit, Art, Abspielen, einzeln oder alle löschen.
private struct NightSoundsSheet: View {
    @Environment(PhoneModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let night: String
    let title: String
    @State private var player = ClipPlayer()
    @State private var confirmAll = false

    var body: some View {
        let clips = model.recorder.nightClips(night)
        NavigationStack {
            List {
                if let summary = soundSummaryText(clips) {
                    Text(summary).font(Fonts.manrope(14, relativeTo: .body))
                }
                Section(L("sound_clips_title")) {
                    ForEach(clips) { clip in
                        row(clip)
                    }
                    .onDelete { offsets in
                        for index in offsets { delete(clips[index]) }
                    }
                }
                if !clips.isEmpty {
                    Button(L("sound_clips_delete_all"), role: .destructive) { confirmAll = true }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .confirmationDialog(L("sound_clips_delete_all"), isPresented: $confirmAll, titleVisibility: .visible) {
                Button(L("sound_clips_delete_all"), role: .destructive) {
                    player.stop()
                    model.recorder.deleteAll(night: night)
                    dismiss()
                }
            }
            .onDisappear { player.stop() }
        }
        .presentationDetents([.medium, .large])
    }

    private func row(_ clip: SoundClip) -> some View {
        let time = Format.time(clip.at)
        let playing = player.playing == clip.file
        return HStack(spacing: 12) {
            Image(systemName: clip.type.symbol).frame(width: 24).foregroundStyle(.secondary).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(time).font(Fonts.manrope(15, .semibold, relativeTo: .headline).monospacedDigit())
                Text(L(clip.type.textKey)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                if playing { player.stop() } else if let url = model.recorder.url(of: clip) { player.play(url, id: clip.file) }
            } label: {
                Image(systemName: playing ? "stop.fill" : "play.fill").frame(width: 44, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L(playing ? "sounds_stop" : "sounds_play"))
            Button(role: .destructive) { delete(clip) } label: {
                Image(systemName: "trash").frame(width: 44, height: 44)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L("sound_clip_delete", time))
        }
        .accessibilityElement(children: .contain)
    }

    private func delete(_ clip: SoundClip) {
        if player.playing == clip.file { player.stop() }
        model.recorder.delete(clip)
    }
}

/// Spielt einen Ausschnitt über den Lautsprecher (Medienlautstärke).
@MainActor
@Observable
private final class ClipPlayer {
    private(set) var playing: String?
    @ObservationIgnored private var player: AVAudioPlayer?
    @ObservationIgnored private var endTask: Task<Void, Never>?

    func play(_ url: URL, id: String) {
        stop()
        // Läuft noch eine Aufnahme oder spielen Klänge, teilt sich der Ausschnitt die Sitzung mit ihnen.
        AudioSessionUse.previewing = true
        guard (try? AudioSessionUse.activate()) != nil, let player = try? AVAudioPlayer(contentsOf: url), player.play() else {
            AudioSessionUse.previewing = false
            AudioSessionUse.release()
            return
        }
        self.player = player
        playing = id
        let duration = player.duration
        endTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(duration + 0.2))
            if !Task.isCancelled { self.stop() }
        }
    }

    func stop() {
        endTask?.cancel()
        endTask = nil
        guard player != nil || playing != nil else { return }
        player?.stop()
        player = nil
        playing = nil
        AudioSessionUse.previewing = false
        AudioSessionUse.release()
    }
}
