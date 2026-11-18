package com.phasewake.app.ui.theme

import com.phasewake.app.core.EveningDark
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** Einstellung «Abends dunkel»: an/aus, Beginn und Ende in Minuten des Tages ([EveningDark]). */
data class EveningDarkSetting(
    val enabled: Boolean = EveningDark.DEFAULT_ENABLED,
    val fromMinutes: Int = EveningDark.DEFAULT_FROM_MINUTES,
    val untilMinutes: Int = EveningDark.DEFAULT_UNTIL_MINUTES,
)

/**
 * Ob «Abends dunkel» gerade gilt, als Flow: sofort der Zustand jetzt, danach genau an jeder Grenze
 * ([EveningDark.nextChange]) der neue – kein Abfragen in festen Abständen. Eine geänderte Einstellung beginnt neu. Wer
 * sammelt, sammelt nur im Vordergrund (`collectAsStateWithLifecycle`): Nach dem Zurückkehren (auch nach einer
 * verstellten Uhr oder Zeitzone) wird wieder ab jetzt gerechnet.
 */
object EveningDarkTicker {
    fun flow(
        settings: Flow<EveningDarkSetting>,
        now: () -> Instant = Instant::now,
        zone: () -> ZoneId = ZoneId::systemDefault,
    ): Flow<Boolean> = channelFlow {
        settings.collectLatest { s ->
            while (true) {
                val at = now()
                val z = zone()
                send(EveningDark.isDark(at, z, s.enabled, s.fromMinutes, s.untilMinutes))
                val next = EveningDark.nextChange(at, z, s.enabled, s.fromMinutes, s.untilMinutes) ?: break
                delay(Duration.between(at, next).toMillis().coerceAtLeast(1L))
            }
        }
    }.distinctUntilChanged()

    /** Zustand jetzt mit den Vorgaben (an, 20:00 bis 06:00): erster Wert, bevor die Einstellungen gelesen sind. */
    fun initial(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Boolean = EveningDark.isDark(
        now, zone, EveningDark.DEFAULT_ENABLED, EveningDark.DEFAULT_FROM_MINUTES, EveningDark.DEFAULT_UNTIL_MINUTES,
    )
}
