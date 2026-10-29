package com.phasewake.app.sounds

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.SoundClip
import com.phasewake.app.core.SoundEventDetector
import com.phasewake.app.core.SoundEvents
import com.phasewake.app.core.SoundType
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.PhoneRingService
import com.phasewake.app.ui.MainActivity
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Geräusche in der Nacht: hört über das Mikrofon des Handys (16 kHz, mono) und behält nur kurze
 * Ausschnitte lauter Geräusche ([SoundEventDetector]), eingeordnet mit [SoundClassifier]. Läuft als
 * Vordergrund-Dienst «microphone» mit Mitteilung zum Stoppen, bis 30 Min nach der Zielzeit des
 * nächsten Weckers oder bis zum Stoppen. Alles bleibt auf dem Handy.
 *
 * Eigene Wiedergabe: Einschlafklänge sind gleichmässig und heben nur das Grundrauschen (die Schwelle
 * passt sich an); solange der Wecker des Handys klingelt, wird nichts erkannt. Keine Echounterdrückung.
 */
class SoundRecorderService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var endsAtMs = 0L
    private var startedAt: Instant? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> {
                // Vom Nutzer gestoppt (Mitteilung oder App): für diese Nacht nicht wieder von selbst starten.
                phoneApp.appScope.launch { phoneApp.settings.setSoundRecChoice(SoundEvents.nightDate(Instant.now(), ZoneId.systemDefault()), false) }
                if (running) running = false else finish()
            }
            else -> if (!running) finish()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        thread?.join(2_000)
        thread = null
        state.value = null
        super.onDestroy()
    }

    private fun start() {
        // Zuerst in den Vordergrund (Pflicht nach startForegroundService), erst dann alles andere.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // Ohne Mikrofon-Recht oder aus dem Hintergrund (ab Android 14 nicht erlaubt).
            Log.w(TAG, "Aufnahme durfte nicht starten", e)
            finish()
            return
        }
        if (running) return
        val now = Instant.now()
        startedAt = now
        // Bis die Wecker gelesen sind: höchstens [SoundEvents.MAX_RUN].
        endsAtMs = SoundEvents.recordingEnd(now, null).toEpochMilli()
        state.value = Recording(Instant.ofEpochMilli(endsAtMs))
        handler.post(refreshEnd)
        running = true
        thread = Thread({ loop() }, "SoundRecorder").apply { start() }
    }

    /**
     * Ende neu bestimmen: 30 Min nach der Zielzeit des ersten Weckers nach Aufnahmebeginn. Wiederholt,
     * damit ein später gestellter oder geänderter Wecker zählt – aber nur bis zu dessen Zielzeit (danach
     * schaltet ein Einmal-Wecker sich aus, und das Ende soll nicht mehr wandern).
     */
    private val refreshEnd = object : Runnable {
        override fun run() {
            val start = startedAt ?: return
            if (System.currentTimeMillis() < endsAtMs - SoundEvents.AFTER_TARGET.toMillis()) {
                phoneApp.appScope.launch {
                    val specs = phoneApp.alarms.current().alarms
                    val end = SoundEvents.recordingEnd(start, AlarmSchedule.earliestPlan(specs, start, ZoneId.systemDefault(), phoneApp.holidays.store.current())?.target)
                    handler.post {
                        if (!running) return@post
                        endsAtMs = end.toEpochMilli()
                        state.value = Recording(end)
                    }
                }
            }
            handler.postDelayed(this, END_REFRESH_MS)
        }
    }

    /** Aufnahme-Thread: liest Blöcke von 100 ms, misst den Pegel und schneidet Ausschnitte heraus. */
    private fun loop() {
        val record = createRecord()
        if (record == null) {
            running = false
            handler.post { finish() }
            return
        }
        val worker = Executors.newSingleThreadExecutor()
        val classifier = SoundClassifier(this)
        val store = phoneApp.sounds
        val zone = ZoneId.systemDefault()
        val frame = ShortArray(FRAME_SAMPLES)
        val ring = ShortArray(RING_SAMPLES)
        var total = 0L
        val detector = SoundEventDetector()
        // Laufender Ausschnitt (Proben ab clipStartSample) und fertige Ausschnitte bis zum Ende ihres Geräuschs.
        var clip: ShortArray? = null
        var clipLength = 0
        var clipStartSample = 0L
        val results = HashMap<Long, Pair<String, SoundType>>()
        var startWall = 0L

        fun handle(action: SoundEventDetector.Action) {
            when (action) {
                is SoundEventDetector.Action.ClipStart -> {
                    // Vorlauf aus dem Ringpuffer (höchstens so weit zurück, wie er reicht).
                    val from = maxOf(action.clipStartMs * SAMPLE_RATE / 1000, total - RING_SAMPLES, 0L)
                    val buffer = ShortArray(MAX_CLIP_SAMPLES)
                    var n = 0
                    var s = from
                    while (s < total && n < buffer.size) {
                        buffer[n++] = ring[(s % RING_SAMPLES).toInt()]
                        s++
                    }
                    clip = buffer
                    clipLength = n
                    clipStartSample = from
                }
                is SoundEventDetector.Action.ClipEnd -> {
                    val buffer = clip ?: return
                    val end = (action.clipEndMs * SAMPLE_RATE / 1000 - clipStartSample).toInt().coerceIn(0, clipLength)
                    clip = null
                    val onset = action.onsetMs
                    worker.execute {
                        val name = "${UUID.randomUUID()}.m4a"
                        val file = store.newFile(name)
                        val type = classifier.classify(buffer, end)
                        if (ClipEncoder.writeM4a(buffer, end, SAMPLE_RATE, file)) results[onset] = name to type
                    }
                }
                is SoundEventDetector.Action.EventEnd -> {
                    val at = Instant.ofEpochMilli(startWall + action.onsetMs)
                    worker.execute {
                        val (name, type) = results.remove(action.onsetMs) ?: return@execute
                        val peak = action.peakDb.takeIf { it.isFinite() }?.roundToInt() ?: 0
                        runBlocking { store.add(SoundClip(SoundEvents.nightDate(at, zone), at, type, action.durationMs, peak, name)) }
                    }
                }
            }
        }

        try {
            worker.execute { runBlocking { store.cleanup() } }
            record.startRecording()
            startWall = System.currentTimeMillis()
            while (running) {
                var read = 0
                while (read < frame.size && running) {
                    val r = record.read(frame, read, frame.size - read)
                    if (r < 0) throw IllegalStateException("AudioRecord.read: $r")
                    read += r
                }
                if (read < frame.size) break
                for (i in frame.indices) ring[((total + i) % RING_SAMPLES).toInt()] = frame[i]
                clip?.let { buffer ->
                    val n = minOf(frame.size, buffer.size - clipLength)
                    if (n > 0) System.arraycopy(frame, 0, buffer, clipLength, n)
                    clipLength += n.coerceAtLeast(0)
                }
                val atMs = total * 1000 / SAMPLE_RATE
                total += frame.size
                // Der eigene Wecker klingelt: kein Geräusch der Nacht.
                if (PhoneRingService.ringState.value !is PhoneRingService.State.Ringing) {
                    detector.onFrame(atMs, SoundEvents.levelDb(frame)).forEach(::handle)
                }
                if (System.currentTimeMillis() >= endsAtMs) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aufnahme abgebrochen", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            detector.finish(total * 1000 / SAMPLE_RATE).forEach(::handle)
            worker.execute { classifier.close() }
            worker.shutdown()
            runCatching { worker.awaitTermination(20, TimeUnit.SECONDS) }
            running = false
            handler.post { finish() }
        }
    }

    private fun createRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                // Eine Sekunde Puffer: kleine Verzögerungen beim Auswerten gehen nicht verloren.
                maxOf(min, SAMPLE_RATE * 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mikrofon nicht verfügbar", e)
            null
        }
    }

    private fun finish() {
        state.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SoundRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(getString(R.string.sound_rec_running))
            .setContentText(getString(R.string.sound_rec_plug_in))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.sound_rec_stop), stop)
            .build()
    }

    /** Laufende Aufnahme für die Oberfläche. */
    data class Recording(val endsAt: Instant)

    companion object {
        private const val TAG = "SoundRecorder"
        const val CHANNEL = "sound_recording"
        private const val NOTIFICATION_ID = 5
        private const val ACTION_START = "com.phasewake.app.sounds.REC_START"
        private const val ACTION_STOP = "com.phasewake.app.sounds.REC_STOP"
        private const val END_REFRESH_MS = 10 * 60_000L
        private const val SAMPLE_RATE = SoundEvents.SAMPLE_RATE
        private val FRAME_SAMPLES = (SAMPLE_RATE * SoundEventDetector.FRAME_MS / 1000).toInt()

        /** Drei Sekunden zurück: genug für den Vorlauf (2 s) und die Erkennung (0,5 s). */
        private const val RING_SAMPLES = SAMPLE_RATE * 3
        private val MAX_CLIP_SAMPLES = (SAMPLE_RATE * SoundEventDetector.MAX_CLIP_MS / 1000).toInt()

        private val state = MutableStateFlow<Recording?>(null)
        val recording: StateFlow<Recording?> = state.asStateFlow()

        /**
         * Startet die Aufnahme bis 30 Min nach der Zielzeit des nächsten Weckers (ohne Wecker höchstens
         * 12 h). Nur aus der sichtbaren App (Mikrofon-Dienst ab Android 14).
         */
        fun start(context: Context) {
            val intent = Intent(context, SoundRecorderService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Aufnahme durfte nicht starten", e)
            }
        }

        /** Beendet die Aufnahme; ohne laufende Aufnahme nichts. */
        fun stop(context: Context) {
            if (state.value != null) context.startService(Intent(context, SoundRecorderService::class.java).setAction(ACTION_STOP))
        }

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.sound_rec_title), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
