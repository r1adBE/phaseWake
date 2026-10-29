package com.phasewake.app.ring

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import com.phasewake.app.R

/**
 * Eigene Wecktöne in `res/raw` (je 28 s, beginnen leise, laufen in Schleife). Im Feld `sound` des
 * Weckers als «raw:<Dateiname>» gespeichert; sonst steht dort die URI eines Systemtons oder nichts
 * (leer = Standard-Weckton des Systems, wie bisher).
 */
object WakeTones {
    class Tone(val key: String, val raw: Int, val label: Int)

    /** Weckton neuer Wecker. */
    const val DEFAULT = "raw:wake_birds"

    val ALL: List<Tone> = listOf(
        Tone("raw:wake_birds", R.raw.wake_birds, R.string.wake_tone_birds),
        Tone("raw:wake_glockenspiel", R.raw.wake_glockenspiel, R.string.wake_tone_glockenspiel),
        Tone("raw:wake_gong", R.raw.wake_gong, R.string.wake_tone_gong),
        Tone("raw:wake_harp", R.raw.wake_harp, R.string.wake_tone_harp),
    )

    /** Eigener Weckton zu [sound]; `null` = Systemton oder Standard. */
    fun find(sound: String): Tone? = ALL.firstOrNull { it.key == sound }

    fun uri(context: Context, tone: Tone): Uri =
        "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${tone.raw}".toUri()

    /** Abspielbare Adresse zu [sound]: eigener Ton, Systemton oder `null` (= Standard-Weckton). */
    fun resolve(context: Context, sound: String): Uri? =
        find(sound)?.let { uri(context, it) } ?: sound.ifEmpty { null }?.toUri()
}

/**
 * Kurzes Probehören eines eigenen Wecktons im Editor: über den Wecker-Kanal (so laut wie später),
 * etwas gedämpft, nach [LENGTH_MS] oder mit [stop] zu Ende. Nur vom Hauptthread benutzen.
 */
class WakeTonePreview(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val end = Runnable { stop() }

    fun play(tone: WakeTones.Tone) {
        stop()
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            created.setDataSource(context, WakeTones.uri(context, tone))
            created.setVolume(VOLUME, VOLUME)
            created.prepare()
            // Die Töne blenden 8 s lang ein: hörbar erst danach.
            created.seekTo(FROM_MS)
            created.start()
            player = created
            handler.postDelayed(end, LENGTH_MS)
        } catch (e: Exception) {
            created.release()
            Log.w("WakeTonePreview", "Weckton ${tone.key} nicht abspielbar", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(end)
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
    }

    private companion object {
        const val FROM_MS = 8_000
        const val LENGTH_MS = 6_000L
        const val VOLUME = 0.6f
    }
}
