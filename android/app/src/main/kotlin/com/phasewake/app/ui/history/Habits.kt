package com.phasewake.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.CustomNote
import com.phasewake.app.core.WeeklySummary
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.notes.noteEffectLine
import java.time.Duration

/**
 * Gewohnheiten für die Trends-Karte; jede Angabe `null`, wenn die Daten sie nicht tragen.
 *
 * @property regular (regelmässige Abende, gezählte Abende) – nur mit Gesundheitsdaten.
 * @property routineAverage Durchschnittlich erledigte Punkte der Abendroutine, dazu [routineItems].
 */
data class HabitLines(
    val regular: Pair<Int, Int>? = null,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val routineAverage: Double? = null,
    val routineItems: Int = 0,
)

/**
 * Zeilen der Gewohnheiten: Regelmässigkeit, Serie, längste Serie, Abendroutine.
 *
 * @param regularShown Regelmässigkeit steht schon im Schlaf-Streifen: hier weglassen.
 */
fun habitLines(
    context: Context,
    habits: HabitLines,
    healthConnected: Boolean,
    regularShown: Boolean = false,
): List<String> {
    val res = context.resources
    return buildList {
        if (healthConnected) {
            habits.regular?.takeIf { it.second > 0 && !regularShown }?.let { (regular, total) ->
                add(res.getQuantityString(R.plurals.habit_regular, total, regular, total))
            }
            if (habits.streak > 0) add(res.getQuantityString(R.plurals.habit_streak, habits.streak, habits.streak))
            if (habits.bestStreak > 0) add(
                res.getQuantityString(R.plurals.habit_streak_best, habits.bestStreak, habits.bestStreak)
            )
        }
        habits.routineAverage?.let {
            add(res.getString(R.string.routine_avg, Format.decimal(res, it), habits.routineItems))
        }
    }
}

/** Der Rückblick in einer Zeile; ohne Gesundheitsdaten nur das Befinden. */
fun weeklyText(context: Context, summary: WeeklySummary): String {
    val feeling = summary.feelingAverage?.let { Format.decimal(context.resources, it) } ?: NONE
    if (!summary.hasSleep) return context.getString(R.string.weekly_notification_feeling, feeling)
    val asleep = summary.asleepAverage?.let { Format.duration(context, Duration.ofMinutes(it)) } ?: NONE
    return context.getString(R.string.weekly_notification_text, asleep, summary.goalReached, summary.regular, feeling)
}

/** Die deutlichste Notiz der Woche, falls eine. */
fun weeklyNoteLine(context: Context, summary: WeeklySummary, custom: List<CustomNote>): String? =
    summary.strongestNote?.let { noteEffectLine(context, it, custom) }

/** «Tiefschlaf 11 % deines Schlafs · Vorwoche 13 %» (beide Wochen mit mindestens 3 Nächten mit Phasen). */
fun weeklyDeepLine(context: Context, deep: Pair<Int, Int>): String =
    context.getString(R.string.weekly_deep, context.getString(R.string.stages_share, deep.first)) + " · " +
        context.getString(R.string.trends_feeling_change, context.getString(R.string.stages_share, deep.second))

/**
 * Karte im Verlauf am Sonntag und Montag: der Rückblick auf die zuletzt abgeschlossene Woche; [deep] Tiefschlaf-Anteil
 * ([weeklyDeepLine]).
 */
@Composable
fun WeeklyCard(summary: WeeklySummary, custom: List<CustomNote>, deep: Pair<Int, Int>? = null) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.weekly_title), style = MaterialTheme.typography.titleSmall)
            Text(weeklyText(context, summary), style = MaterialTheme.typography.bodyMedium)
            deep?.let { Text(weeklyDeepLine(context, it), style = MaterialTheme.typography.bodyMedium) }
            weeklyNoteLine(context, summary, custom)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** Platzhalter für einen fehlenden Wert. */
private const val NONE = "–"
