package com.phasewake.app.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.DetectorConfig
import com.phasewake.app.core.LearnNights
import com.phasewake.app.core.LearnNights.Reason
import com.phasewake.app.core.Learning
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.settings.MIN_TOUCH
import java.time.Duration

/**
 * Lern-Übersicht (Entscheid r1AD 13.11.2026, aus «Details» der Karte der Lernphase auf der Startseite und in den
 * Einstellungen): je Nacht seit dem Beginn eine Zeile mit Datum (Morgen der Nacht, wie im Verlauf), ✓ gezählt bzw. ✗ mit
 * Grund, kurzen Angaben zur Aufzeichnung und einem Tipp. [nights]: jüngste zuerst ([LearnNights.nights]). [goodNight]: Die
 * Uhr bietet «Gute Nacht» an ([goodNightOffered]); der Tipp bei «Einschlafen automatisch erkennen» aus richtet sich danach.
 */
@Composable
fun LearnNightsScreen(nights: List<LearnNights.Night>, onBack: () -> Unit, goodNight: Boolean = true) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.onb_back)) }
            Text(
                stringResource(R.string.learn_consent_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.learn_nights_intro, Learning.NIGHTS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (nights.isEmpty()) {
                item(key = "empty") { Text(stringResource(R.string.learn_nights_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(nights, key = { it.date.toString() }) { NightRow(it, goodNight) }
        }
    }
}

/** Eine Nacht: Datum, ✓/✗, Grund, Angaben und Tipp; für TalkBack eine Einheit (Symbol ohne eigene Beschreibung). */
@Composable
private fun NightRow(night: LearnNights.Night, goodNight: Boolean) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val ok = night.reason.counted
    val expected = night.reason in EXPECTED
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = cardColor()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH).semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_close),
                contentDescription = null,
                tint = when {
                    ok -> MaterialTheme.colorScheme.primary
                    expected -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 2.dp).size(22.dp),
            )
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(Format.shortDay(night.date.plusDays(1), locale), style = MaterialTheme.typography.titleSmall)
                Text(learnReasonText(context, night), style = MaterialTheme.typography.bodyMedium)
                learnFactsText(context, night)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                learnTipText(context, night.reason, goodNight)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Gründe ohne Fehler (✗ gedämpft statt rot): erwartet bzw. noch offen. */
private val EXPECTED = setOf(Reason.NO_ALARM, Reason.PENDING, Reason.LEARNING_OFF)

/** Grund einer Nacht in Worten (Übersicht und Startseite). */
internal fun learnReasonText(context: Context, night: LearnNights.Night): String = when (night.reason) {
    Reason.COUNTED -> context.getString(R.string.learn_reason_counted)
    Reason.COUNTED_NO_PULSE -> context.getString(R.string.learn_reason_counted_no_pulse)
    Reason.TOO_SHORT -> context.getString(
        R.string.learn_reason_too_short,
        Format.duration(context, Duration.ofMinutes((night.minutes ?: 0).toLong())),
        Format.duration(context, Duration.ofSeconds(Learning.MIN_SPAN_EPOCHS.toLong() * DetectorConfig.EPOCH_SECONDS)),
    )
    Reason.TOO_FEW -> context.getString(R.string.learn_reason_too_few, night.measuredEpochs ?: 0, Learning.MIN_MEASURED_EPOCHS)
    Reason.ENDED_STOPPED -> context.getString(R.string.learn_reason_stopped)
    Reason.ENDED_BATTERY -> context.getString(R.string.learn_reason_ended_battery)
    Reason.ENDED_SERVICE -> context.getString(R.string.learn_reason_ended_system)
    Reason.NOT_WORN -> context.getString(R.string.learn_reason_not_worn)
    Reason.BATTERY -> night.battery?.let { context.getString(R.string.learn_reason_battery, it) } ?: context.getString(R.string.learn_reason_battery_low)
    Reason.SENSORS -> context.getString(R.string.learn_reason_sensors)
    Reason.MANUAL -> context.getString(R.string.learn_reason_manual)
    Reason.LATE -> context.getString(R.string.learn_reason_late)
    Reason.LEARNING_OFF -> context.getString(R.string.learn_reason_off)
    Reason.NO_ALARM -> context.getString(R.string.learn_reason_no_alarm)
    Reason.PENDING -> context.getString(R.string.learn_reason_pending)
    Reason.AUTO_OFF -> context.getString(R.string.learn_reason_auto_off)
    Reason.NO_RECORDING, Reason.UNKNOWN -> context.getString(R.string.learn_reason_none)
}

/** Kurze Angaben zur Aufzeichnung (gemessene Spanne, Messwerte, Pulsanteil); `null` ohne Aufzeichnung. */
internal fun learnFactsText(context: Context, night: LearnNights.Night): String? {
    // «Zu kurz» nennt die gemessene Spanne schon im Grund: keine zweite Zeile mit denselben Minuten.
    if (night.reason == LearnNights.Reason.TOO_SHORT) return null
    val minutes = night.minutes ?: return null
    return context.getString(
        R.string.learn_night_facts,
        Format.duration(context, Duration.ofMinutes(minutes.toLong())),
        night.measuredEpochs ?: 0,
        night.pulsePercent ?: 0,
    )
}

/** Kurzer Tipp zum Grund; `null`, wenn es nichts zu tun gibt. */
internal fun learnTipText(context: Context, reason: Reason, goodNight: Boolean = true): String? = when (reason) {
    Reason.COUNTED, Reason.LEARNING_OFF -> null
    Reason.COUNTED_NO_PULSE, Reason.NOT_WORN -> context.getString(R.string.learn_tip_worn)
    Reason.TOO_SHORT, Reason.ENDED_STOPPED -> context.getString(R.string.learn_tip_run)
    Reason.TOO_FEW, Reason.ENDED_SERVICE -> context.getString(R.string.learn_tip_system)
    Reason.ENDED_BATTERY, Reason.BATTERY -> context.getString(R.string.learn_tip_charge, Learning.MIN_BATTERY)
    Reason.SENSORS -> context.getString(R.string.learn_tip_sensors)
    Reason.LATE -> context.getString(R.string.learn_tip_late)
    Reason.NO_ALARM -> context.getString(R.string.learn_tip_alarm)
    Reason.AUTO_OFF -> context.getString(learnAutoHint(goodNight))
    Reason.MANUAL, Reason.PENDING, Reason.NO_RECORDING, Reason.UNKNOWN -> context.getString(R.string.learn_tip_connect)
}
