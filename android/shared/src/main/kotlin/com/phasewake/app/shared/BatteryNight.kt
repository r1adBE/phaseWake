package com.phasewake.app.shared

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.phasewake.app.core.BatteryPoint
import com.phasewake.app.core.BatteryTrack
import java.time.Instant

/**
 * Akkustand je Nacht auf diesem Gerät (Handy oder Uhr, r1AD 14.11.2026); die Rechnung steht in [BatteryTrack]. Die
 * laufende Nacht liegt in einer kleinen Datei (SharedPreferences) und übersteht so ein Prozessende.
 *
 * Ladegerät: Solange der Prozess lebt, meldet [listen] Anschliessen und Trennen sofort. Läuft er nicht, startet der Auftrag
 * [BatteryChargeJob] (JobScheduler, nur am Ladegerät) beim Anschliessen und hält den Ladebeginn fest. Ein Wechsel, den
 * keiner sieht, macht die Nacht unbekannt (kein Wert statt eines falschen, siehe [BatteryTrack.used]).
 * Vor dem ersten Entsperren (Direct Boot) ist der Speicher nicht lesbar: dann geschieht nichts.
 */
object BatteryNight {
    /** Akkustand jetzt: Prozent 0–100 und ob am Ladegerät. */
    data class Reading(val at: Instant, val percent: Int, val plugged: Boolean)

    /** Ins Diagnose-Protokoll (Art BATTERY); setzt die App beim Start. */
    @Volatile
    var log: (String) -> Unit = {}

    private const val PREFS = "battery_night"
    private const val KEY_OPEN = "open"
    private const val KEY_DONE = "done"

    /** Kennung des Auftrags [BatteryChargeJob]; die Apps haben keine anderen Aufträge. */
    internal const val JOB_ID = 0x5057_0001

    private val lock = Any()

    /** Akkustand aus der Systemmeldung ACTION_BATTERY_CHANGED (bleibt stehen, kein Empfänger nötig); unbekannt: `null`. */
    fun read(context: Context): Reading? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Reading(Instant.ofEpochMilli(System.currentTimeMillis()), (level * 100 / scale).coerceIn(0, 100), plugged)
    }

    /**
     * Messbeginn der Nacht ([BatteryTrack.begin]): Läuft schon eine, kommt ein Zwischenstand dazu. `true`, wenn damit eine
     * neue Nacht beginnt (die Uhr meldet das dem Handy).
     */
    fun begin(context: Context, reading: Reading? = read(context)): Boolean {
        if (reading == null || !unlocked(context)) return false
        val fresh = synchronized(lock) {
            val before = open(context)
            val after = BatteryTrack.begin(before, reading.at, reading.percent, reading.plugged)
            if (after != before) {
                save(context, KEY_OPEN, after)
                log("night battery ${BatteryTrack.describe(after.last())}")
            }
            !BatteryTrack.isOpen(before, reading.at)
        }
        if (!reading.plugged) arm(context)
        return fresh
    }

    /** Zwischenstand in einer laufenden Nacht. */
    fun sample(context: Context, reading: Reading? = read(context)) {
        if (reading == null || !unlocked(context)) return
        change(context) { BatteryTrack.sample(it, reading.at, reading.percent, reading.plugged) }
    }

    /** Ladegerät angeschlossen oder getrennt ([reading] mit dem neuen Stand): Ladebeginn bzw. -ende festhalten. */
    fun charged(context: Context, reading: Reading?) {
        if (reading == null || !unlocked(context)) return
        val running = change(context) { BatteryTrack.charge(it, reading.at, reading.percent, reading.plugged) }
        // Getrennt: das nächste Anschliessen wieder erkennen, auch wenn der Prozess dann nicht läuft.
        if (running && !reading.plugged) arm(context)
    }

    /** Ende der Nacht: die fertigen Punkte (ins Protokoll mit Verbrauch); `null`, wenn keine lief. */
    fun end(context: Context, reading: Reading? = read(context)): List<BatteryPoint>? {
        if (!unlocked(context)) return null
        disarm(context)
        val track = synchronized(lock) {
            val open = open(context)
            if (open.isEmpty()) return null
            save(context, KEY_OPEN, null)
            reading?.let { BatteryTrack.end(open, it.at, it.percent, it.plugged) }
        }
        if (track == null) {
            log("night battery end unknown")
            return null
        }
        log("night battery ${BatteryTrack.describe(track.last())}, used ${BatteryTrack.used(track)?.let { "$it%" } ?: "unknown"}")
        return track
    }

    /** Laufende Nacht verwerfen ([why] ins Protokoll), z. B. Aufzeichnung ohne Wecker beendet. */
    fun drop(context: Context, why: String) {
        if (!unlocked(context)) return
        disarm(context)
        val had = synchronized(lock) {
            val open = open(context)
            if (open.isNotEmpty()) save(context, KEY_OPEN, null)
            open.isNotEmpty()
        }
        if (had) log("night battery dropped: $why")
    }

    /** Handy: beendete Nacht merken, bis ihre Nacht von der Uhr da ist ([takeDone]). */
    fun keepDone(context: Context, track: List<BatteryPoint>) {
        if (!unlocked(context)) return
        synchronized(lock) { save(context, KEY_DONE, track) }
    }

    /** Handy: gemerkte beendete Nacht, wenn sie zu [matches] passt; dann vergessen. */
    fun takeDone(context: Context, matches: (List<BatteryPoint>) -> Boolean): List<BatteryPoint>? {
        if (!unlocked(context)) return null
        return synchronized(lock) {
            val done = BatteryTrack.decode(prefs(context).getString(KEY_DONE, null))?.takeIf(matches)
            if (done != null) save(context, KEY_DONE, null)
            done
        }
    }

    /** Anschliessen und Trennen melden, solange der Prozess lebt (im Manifest kommen diese Meldungen nicht an). */
    fun listen(context: Context) {
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    charged(context, read(context)?.copy(plugged = intent.action == Intent.ACTION_POWER_CONNECTED))
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Ändert die laufende Nacht und protokolliert einen neuen Punkt; `true`, wenn eine läuft. */
    private fun change(context: Context, block: (List<BatteryPoint>) -> List<BatteryPoint>): Boolean = synchronized(lock) {
        val before = open(context)
        val after = block(before)
        if (after != before) {
            save(context, KEY_OPEN, after)
            log("night battery ${BatteryTrack.describe(after.last())}")
        }
        before.isNotEmpty()
    }

    /** Auftrag fürs nächste Anschliessen ([BatteryChargeJob]); ersetzt einen geplanten. */
    private fun arm(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, BatteryChargeJob::class.java)).setRequiresCharging(true).build()
        runCatching { scheduler.schedule(job) }.onFailure { log("night battery charge watch not planned: ${it.javaClass.simpleName}") }
    }

    private fun disarm(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    private fun unlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun open(context: Context): List<BatteryPoint> = BatteryTrack.decode(prefs(context).getString(KEY_OPEN, null)).orEmpty()

    private fun save(context: Context, key: String, track: List<BatteryPoint>?) {
        prefs(context).edit(commit = true) { if (track.isNullOrEmpty()) remove(key) else putString(key, BatteryTrack.encode(track)) }
    }
}

/**
 * Läuft, sobald das Gerät am Ladegerät hängt, auch wenn die App gerade nicht läuft ([BatteryNight]): hält den Ladebeginn
 * der laufenden Nacht fest. Kurz und ohne Arbeit im Hintergrund.
 */
class BatteryChargeJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        BatteryNight.read(this)?.takeIf { it.plugged }?.let { BatteryNight.charged(this, it) }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
