import PhaseWakeCore
import SwiftUI

extension SleepSound {
    /// Textschlüssel wie Android («sound_white» …).
    var textKey: String { "sound_" + rawValue.lowercased() }

    var symbol: String {
        switch self {
        case .white: "waveform"
        case .pink: "waveform.path"
        case .brown: "waveform.path.ecg"
        case .fan: "fan"
        case .binaural: "headphones"
        case .rain: "cloud.rain"
        case .ocean: "water.waves"
        case .forest: "leaf"
        case .fire: "flame"
        case .brook: "drop"
        }
    }
}

/// Startseite: Einstieg zu den Einschlafklängen; zeigt, was gerade spielt.
struct SoundsButton: View {
    @Environment(PhoneModel.self) private var model
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Label(L("sounds_title"), systemImage: "moon.zzz").font(Fonts.manrope(16, .semibold, relativeTo: .headline))
                Spacer()
                if let playing = model.sounds.playing {
                    Text(L(playing.textKey)).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                }
            }
        }
        .buttonStyle(.plain)
        .card()
    }
}

/// Einschlafklänge: zehn Klänge (Rauschen, Ventilator, Einschlaf-Wellen, Aufnahmen), Timer, Start/Stopp, Restzeit.
struct SoundsSheet: View {
    @Environment(PhoneModel.self) private var model
    @AppStorage("soundChoice") private var choice = SleepSound.pink.rawValue
    /// Timer in Minuten; 0 = ohne Timer.
    @AppStorage("soundTimerMinutes") private var timer = SleepSounds.defaultTimerMinutes

    var body: some View {
        let player = model.sounds
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        ForEach(SleepSound.allCases, id: \.self) { sound in
                            tile(sound, selected: sound.rawValue == choice, playing: player.playing == sound)
                        }
                    }
                    // Einschlaf-Wellen: links und rechts ein anderer Ton, darum mit Kopfhörern hören.
                    if (player.playing ?? SleepSound(rawValue: choice)) == .binaural {
                        Text(L("sound_binaural_hint")).font(Fonts.manrope(14, relativeTo: .subheadline)).foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text(L("sounds_timer")).font(Fonts.manrope(15, .semibold, relativeTo: .headline))
                        Picker(L("sounds_timer"), selection: $timer) {
                            ForEach(SleepSounds.timerOptions, id: \.self) { minutes in
                                Text(minutes.map { Format.duration(minutes: $0) } ?? L("sounds_timer_off")).tag(minutes ?? 0)
                            }
                        }
                        .pickerStyle(.segmented)
                        .onChange(of: timer) { _, _ in
                            // Läuft schon etwas: mit dem neuen Timer neu starten.
                            if let playing = player.playing { start(playing) }
                        }
                    }
                    Text(L("sounds_hint")).font(Fonts.manrope(12, relativeTo: .caption)).foregroundStyle(.secondary)
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        VStack(spacing: 10) {
                            let canPlay = windowStart(now: context.date).map { $0 > context.date } ?? true
                            Button {
                                if player.playing != nil { player.stop() } else { start(SleepSound(rawValue: choice) ?? .pink) }
                            } label: {
                                Label(L(player.playing != nil ? "sounds_stop" : "sounds_play"),
                                      systemImage: player.playing != nil ? "stop.fill" : "play.fill")
                                    .font(Fonts.manrope(17, .bold, relativeTo: .headline))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 8)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(player.playing == nil && !canPlay)
                            if player.playing != nil, let end = player.endsAt {
                                Text(L("sounds_remaining", remaining(end.timeIntervalSince(context.date))))
                                    .font(Fonts.manrope(13, relativeTo: .footnote).monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(L("sounds_title"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.large])
    }

    private func tile(_ sound: SleepSound, selected: Bool, playing: Bool) -> some View {
        Button {
            choice = sound.rawValue
            // Während der Wiedergabe wechselt ein Tipp den Klang.
            if model.sounds.playing != nil, !playing { start(sound) }
        } label: {
            VStack(spacing: 8) {
                Image(systemName: sound.symbol).font(.title2)
                Text(L(sound.textKey)).font(Fonts.manrope(14, .semibold, relativeTo: .subheadline)).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity, minHeight: 88)
            .background(selected ? Color.accentColor.opacity(0.22) : Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(playing ? Color.accentColor : .clear, lineWidth: 2))
            // Gewählt: Haken oben rechts, damit die Wahl nicht nur an der Farbe hängt (WCAG 1.4.1).
            .overlay(alignment: .topTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.accentColor).padding(8).accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Beginn des nächsten Weckfensters: Dort enden die Klänge spätestens.
    private func windowStart(now: Date) -> Date? {
        AlarmSchedule.earliestPlan(model.alarms, now: now, doneUntil: model.doneUntil, calendar: .current)?.windowStart
    }

    private func start(_ sound: SleepSound) {
        model.sounds.play(sound, timerMinutes: timer == 0 ? nil : timer, nextWindowStart: windowStart(now: Date()))
    }

    /// «12:34» bzw. «1:02:03».
    private func remaining(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.up)))
        let h = total / 3600, m = total / 60 % 60, s = total % 60
        let mm = m < 10 ? "0\(m)" : "\(m)", ss = s < 10 ? "0\(s)" : "\(s)"
        return h > 0 ? "\(h):\(mm):\(ss)" : "\(m):\(ss)"
    }
}
