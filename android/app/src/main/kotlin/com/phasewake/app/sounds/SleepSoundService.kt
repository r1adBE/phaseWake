package com.phasewake.app.sounds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.NoiseGenerator
import com.phasewake.app.core.SleepSound
import com.phasewake.app.core.SleepSounds
import com.phasewake.app.phoneApp
import com.phasewake.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Einschlafklänge: erzeugt das Rauschen fortlaufend ([NoiseGenerator]) oder spielt eine Aufnahme nahtlos
 * in Schleife ([LoopedRecording]), beides über einen AudioTrack auf dem Medien-Kanal (Medienlautstärke,
 * nie der Wecker-Kanal). Vordergrund-Dienst «mediaPlayback» mit Mitteilung zum Stoppen. Endet nach dem Timer oder spätestens zu Beginn des
 * nächsten Weckfensters, mit 30 s Ausblenden; verliert er den Audio-Fokus, pausiert oder stoppt er.
 * Das Ende wird jede Minute mit dem aktuellen Plan verglichen (geänderte Wecker); klingelt die Uhr, endet
 * er sofort (siehe [BackupAlarm.watchRang][com.phasewake.app.ring.BackupAlarm.watchRang]).
 */
class SleepSoundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null

    // Zwischen Hauptthread und Audio-Thread geteilt.
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var pendingSound: SleepSound? = null
    @Volatile private var endsAtMs: Long = NO_END

    /**
     * Jede Minute: Beginnt das nächste Weckfenster laut aktuellem Plan früher als das bisherige Ende
     * (Wecker später gestellt oder geändert), endet der Klang dort. Nur kürzen, nie verlängern.
     * Das Fenster eines Schlafdauer-Weckers nach dem Einschlafen kennt nur die Uhr; dort hilft das
     * Stoppen beim Klingeln der Uhr.
     */
    private val recheckEnd = object : Runnable {
        override fun run() {
            val app = phoneApp
            app.appScope.launch {
                val now = Instant.now()
                val window = AlarmSchedule.earliestPlan(app.alarms.current().alarms, now, ZoneId.systemDefault(), app.holidays.store.current())?.windowStart ?: return@launch
                val end = maxOf(window, now).toEpochMilli()
                handler.post {
                    val playing = state.value ?: return@post
                    if (end >= endsAtMs) return@post
                    endsAtMs = end
                    state.value = playing.copy(endsAt = Instant.ofEpochMilli(end))
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(playing.sound, end))
                }
            }
            handler.postDelayed(this, RECHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val sound = intent.getStringExtra(EXTRA_SOUND)?.let { name -> SleepSound.entries.firstOrNull { it.name == name } } ?: SleepSound.PINK
                val endsAt = if (intent.hasExtra(EXTRA_ENDS_AT)) intent.getLongExtra(EXTRA_ENDS_AT, NO_END) else NO_END
                play(sound, endsAt)
            }
            ACTION_STOP -> if (running) stopRequested = true else finish()
            else -> if (!running) finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        thread?.join(500)
        thread = null
        abandonFocus()
        state.value = null
        super.onDestroy()
    }

    private fun play(sound: SleepSound, endsAt: Long) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(sound, endsAt), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        endsAtMs = endsAt
        stopRequested = false
        state.value = Playing(sound, endsAt.takeIf { it != NO_END }?.let(Instant::ofEpochMilli))
        handler.removeCallbacks(recheckEnd)
        handler.postDelayed(recheckEnd, RECHECK_MS)
        if (running) {
            // Läuft schon: kurz aus- und mit dem neuen Klang wieder einblenden.
            pendingSound = sound
            return
        }
        if (!requestFocus()) {
            Log.i(TAG, "Kein Audio-Fokus, keine Einschlafklänge")
            finish()
            return
        }
        running = true
        paused = false
        pendingSound = null
        thread = Thread({ loop(sound) }, "SleepSound").apply { start() }
    }

    /** Audio-Thread: spielt Blöcke von 50 ms, Lautstärke je Block weich übergeblendet. */
    private fun loop(initial: SleepSound) {
        var source = openSource(initial) ?: run {
            running = false
            handler.post { finish() }
            return
        }
        var track = try {
            buildTrack(source)
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack nicht verfügbar", e)
            running = false
            handler.post { finish() }
            return
        }
        var block = FloatArray(source.sampleRate / 20 * source.channels)
        var startedAt = System.currentTimeMillis()
        var lastGain = 0f
        // Kurzes Ausblenden beim Stoppen oder Wechseln (1 = voll, 0 = aus).
        var quick = 1f
        try {
            track.play()
            while (running) {
                if (paused) {
                    track.pause()
                    while (running && paused && !stopRequested) Thread.sleep(100)
                    if (running) track.play()
                    lastGain = 0f
                    continue
                }
                val now = System.currentTimeMillis()
                val switching = pendingSound != null
                if (stopRequested || switching) quick = (quick - BLOCK_MS / QUICK_FADE_MS).coerceAtLeast(0f)
                val untilEnd = endsAtMs.takeIf { it != NO_END }?.let { it - now }
                val target = SleepSounds.gain(now - startedAt, untilEnd) * quick
                source.fill(block)
                // Je Abtastzeitpunkt (alle Kanäle gleich) weich von der alten zur neuen Lautstärke.
                val channels = source.channels
                val frames = block.size / channels
                for (f in 0 until frames) {
                    val gain = lastGain + (target - lastGain) * (f + 1) / frames
                    for (c in 0 until channels) block[f * channels + c] *= gain
                }
                lastGain = target
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
                when {
                    quick <= 0f && switching && !stopRequested -> {
                        val next = pendingSound ?: source.sound
                        pendingSound = null
                        val opened = openSource(next) ?: break
                        // Andere Abtastrate oder Kanalzahl (Aufnahme oder Einschlaf-Wellen statt Rauschen): neuer AudioTrack.
                        if (opened.sampleRate != source.sampleRate || opened.channels != source.channels) {
                            runCatching { track.stop() }
                            track.release()
                            track = buildTrack(opened)
                            track.play()
                        }
                        source = opened
                        block = FloatArray(source.sampleRate / 20 * source.channels)
                        startedAt = System.currentTimeMillis()
                        lastGain = 0f
                        quick = 1f
                    }
                    quick <= 0f || (untilEnd != null && untilEnd <= 0) -> break
                }
            }
        } catch (_: InterruptedException) {
            // Dienst beendet.
        } catch (e: Exception) {
            Log.w(TAG, "Einschlafklänge abgebrochen", e)
        } finally {
            runCatching { track.stop() }
            track.release()
            running = false
            handler.post { finish() }
        }
    }

    /**
     * Quelle für [sound]: erzeugt (Rauschen, Ventilator, Einschlaf-Wellen) oder als Aufnahme einmal dekodiert und nahtlos
     * in Schleife. `null`, wenn die Aufnahme nicht lesbar ist oder der Dienst inzwischen endet.
     */
    private fun openSource(sound: SleepSound): SoundSource? {
        val raw = rawRecording(sound) ?: return GeneratedSound(NoiseGenerator(sound, SAMPLE_RATE, System.nanoTime()), SAMPLE_RATE)
        return try {
            LoopedSound(sound, LoopedRecording.decode(this, raw) { !running })
        } catch (_: InterruptedException) {
            null
        } catch (e: Exception) {
            Log.w(TAG, "Aufnahme $sound nicht lesbar", e)
            null
        }
    }

    private fun buildTrack(source: SoundSource): AudioTrack {
        val mask = if (source.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(source.sampleRate)
            .setChannelMask(mask)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(source.sampleRate, mask, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(format)
            // Etwa 200 ms Puffer: genug gegen Aussetzer, kurz genug für ein promptes Stoppen.
            .setBufferSizeInBytes(maxOf(minBuffer, source.sampleRate / 5 * source.channels * Float.SIZE_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestFocus(): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRIBUTES)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> stopRequested = true
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> paused = true
                    AudioManager.AUDIOFOCUS_GAIN -> paused = false
                }
            }, handler)
            .build()
        focusRequest = request
        return audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun finish() {
        handler.removeCallbacks(recheckEnd)
        state.value = null
        abandonFocus()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(sound: SleepSound, endsAt: Long): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SleepSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(getString(R.string.sounds_title))
            .setContentText(getString(soundName(sound)))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.sounds_stop), stop)
        // Restzeit läuft in der Mitteilung rückwärts.
        if (endsAt != NO_END) builder.setWhen(endsAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        return builder.build()
    }

    /** Laufender Klang für die Oberfläche; [endsAt] `null` = ohne Ende. */
    data class Playing(val sound: SleepSound, val endsAt: Instant?)

    companion object {
        private const val TAG = "SleepSoundService"
        const val CHANNEL = "sounds"
        private const val NOTIFICATION_ID = 4
        private const val ACTION_PLAY = "com.phasewake.app.sounds.PLAY"
        private const val ACTION_STOP = "com.phasewake.app.sounds.STOP"
        private const val EXTRA_SOUND = "sound"
        private const val EXTRA_ENDS_AT = "endsAt"
        private const val NO_END = Long.MAX_VALUE
        private const val SAMPLE_RATE = 48_000
        private const val BLOCK_MS = 50f
        private const val QUICK_FADE_MS = 300f

        /** So oft wird das Ende mit dem aktuellen Plan verglichen. */
        private const val RECHECK_MS = 60_000L

        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        private val state = MutableStateFlow<Playing?>(null)
        val playing: StateFlow<Playing?> = state.asStateFlow()


        fun soundName(sound: SleepSound): Int = when (sound) {
            SleepSound.WHITE -> R.string.sound_white
            SleepSound.PINK -> R.string.sound_pink
            SleepSound.BROWN -> R.string.sound_brown
            SleepSound.FAN -> R.string.sound_fan
            SleepSound.BINAURAL -> R.string.sound_binaural
            SleepSound.RAIN -> R.string.sound_rain
            SleepSound.OCEAN -> R.string.sound_ocean
            SleepSound.FOREST -> R.string.sound_forest
            SleepSound.FIRE -> R.string.sound_fire
            SleepSound.BROOK -> R.string.sound_brook
        }

        /** Datei der Aufnahme in `res/raw`; `null` = im Code erzeugt. */
        private fun rawRecording(sound: SleepSound): Int? = when (sound) {
            SleepSound.WHITE, SleepSound.PINK, SleepSound.BROWN, SleepSound.FAN, SleepSound.BINAURAL -> null
            SleepSound.RAIN -> R.raw.sound_rain
            SleepSound.OCEAN -> R.raw.sound_ocean
            SleepSound.FOREST -> R.raw.sound_forest
            SleepSound.FIRE -> R.raw.sound_fire
            SleepSound.BROOK -> R.raw.sound_brook
        }

        /** Startet oder wechselt den Klang; [endsAt] `null` = ohne Ende. Nur aus der sichtbaren App. */
        fun play(context: Context, sound: SleepSound, endsAt: Instant?) {
            val intent = Intent(context, SleepSoundService::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_SOUND, sound.name)
            if (endsAt != null) intent.putExtra(EXTRA_ENDS_AT, endsAt.toEpochMilli())
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Einschlafklänge durften nicht starten", e)
            }
        }

        /** Blendet kurz aus und beendet; ohne laufenden Klang nichts. */
        fun stop(context: Context) {
            if (state.value != null) context.startService(Intent(context, SleepSoundService::class.java).setAction(ACTION_STOP))
        }

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.sounds_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
