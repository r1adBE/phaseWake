package com.phasewake.app.shared

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.CodecException
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientPlan
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Der eine vorübergehende Plan (Probewecker oder Powernap), auf Handy und Uhr je gespeichert. Ein neuer
 * ersetzt den alten; abgelaufene ([Transient.isActive]) gelten nicht mehr. Kein gespeicherter Wecker.
 */
class TransientStore(
    private val store: DataStore<Preferences>,
    private val clock: () -> Instant = Instant::now,
) {
    /** Gespeicherter Plan, auch abgelaufen; `null`, wenn keiner oder unlesbar. */
    val stored: Flow<TransientPlan?> = store.data.map { decode(it[KEY]) }.distinctUntilChanged()

    /** Der gültige Plan jetzt, oder `null`. */
    suspend fun active(): TransientPlan? = stored.first()?.takeIf { Transient.isActive(it, clock()) }

    /** Weckart des vorübergehenden Plans [alarmId], falls er der gespeicherte ist (auch abgelaufen). */
    suspend fun specFor(alarmId: String?): AlarmSpec? =
        if (Transient.kindOf(alarmId) == null) null else stored.first()?.spec?.takeIf { it.id == alarmId }

    /** Speichert [plan] und ersetzt einen früheren. */
    suspend fun set(plan: TransientPlan) {
        store.edit { it[KEY] = Transient.encode(plan) }
    }

    /** Entfernt den Plan; mit [alarmId] nur, wenn es dieser ist. @return ob etwas entfernt wurde. */
    suspend fun clear(alarmId: String? = null): Boolean {
        var removed = false
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            if (prefs[KEY] != null && (alarmId == null || current?.spec?.id == alarmId)) {
                prefs.remove(KEY)
                removed = true
            }
        }
        return removed
    }

    private fun decode(text: String?): TransientPlan? {
        if (text.isNullOrEmpty()) return null
        return try {
            Transient.decode(text)
        } catch (e: CodecException) {
            Log.w(TAG, "Vorübergehender Plan unlesbar", e)
            null
        }
    }

    private companion object {
        const val TAG = "TransientStore"
        val KEY = stringPreferencesKey("transient_plan")
    }
}
