package com.phasewake.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Learning
import com.phasewake.app.core.ShortNight
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.WakePlan
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.SkyMood
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// Abgeleitete Werte der Startseite: nächster Plan, Feiertag, automatische Messung, Abend und Morgen.

/** Abgeleitete Werte der Startseite zum Zeitpunkt [now]; [advice] = empfohlene Schlafenszeit (von, bis). */
internal data class HomeView(
    val now: Instant,
    val zone: ZoneId,
    val locale: Locale,
    val plan: WakePlan?,
    val holidayNote: String?,
    val autoOnset: String?,
    val morningFirst: Boolean,
    val evening: Boolean,
    val advice: Pair<Instant, Instant>?,
    val dark: Boolean,
    val mood: SkyMood,
)

/** Werte der Startseite ([HomeView]) aus [state] und [extras] zum Zeitpunkt [now]. */
@Composable
internal fun rememberHomeView(state: HomeState, extras: HomeExtras, now: Instant): HomeView {
    val alarms = state.alarms
    val check = state.check
    val lastNight = state.lastNight
    val zone = ZoneId.systemDefault()
    // Eine schon erledigte Nacht (früh geweckt, Zielzeit noch vorn) ist nicht mehr der nächste Wecker.
    val done = extras.doneUntil
    val plan = remember(alarms, now, extras.holidays, done) {
        AlarmSchedule.earliestPlan(alarms, now, zone, extras.holidays, done)
    }
    val locale = LocalConfiguration.current.locales[0]
    // Feiertag, an dem ein Wecker sonst als Nächstes geweckt hätte (nur bis zum nächsten Weckfenster).
    val holidayNote = remember(alarms, now, extras.holidays, done) {
        AlarmSchedule.holidayNote(alarms, now, zone, extras.holidays, done)?.let { Format.shortDay(it, locale) }
    }

    // Automatische Einschlaf-Messung der Uhr (nur Galaxy Watch), solange das Fenster noch nicht begonnen hat und die
    // Uhr bereit ist.
    val watchOk = check != null && check.watchChecked && check.failed.none { it in WATCH_PROBLEMS }
    // Akku der Uhr zu schwach: Sie misst das Einschlafen nicht, also keine Zeile dazu (der Check sagt es).
    val autoOnset = plan?.takeIf {
        extras.autoOnset && watchOk && !NightCheckState.onsetSkipped(check) && now.isBefore(it.windowStart)
    }?.let { p ->
        alarms.firstOrNull { it.id == p.alarmId }?.let { a ->
            Format.time(ShortNight.autoOnsetStart(p.target, extras.sleepGoal, a.sleepDurationMinutes), zone)
        }
    }
    // Morgenbericht zuerst: vom Wecken heute bis 12 Uhr steht die letzte Nacht direkt unter dem Kopf.
    val morningFirst = lastNight != null && isMorningOf(lastNight, now, zone)
    val evening = SleepNotes.entryVisible(now, zone, plan?.windowStart)
    val advice = if (evening) bedtimeAdvice(plan, alarms, extras, now, zone) else null
    // Dunkel: die ganze Seite ist Nachthimmel. Hell: nur der Kopfbereich, darunter helle Karten.
    val dark = PhaseWakeTheme.isDark
    // Himmel nach Tageszeit: Abendrot, Nacht, Morgendämmerung auf das Weckfenster zu.
    val mood = SkyMood.at(now, zone, plan?.windowStart, plan?.target)
    return HomeView(now, zone, locale, plan, holidayNote, autoOnset, morningFirst, evening, advice, dark, mood)
}

/**
 * Empfohlene Schlafenszeit ([Learning.bedtimeRange]) für den Wecker [plan] dieser Nacht, nur nach der Lernphase
 * ([HomeExtras.learned]) und bis zu ihrem Ende; Schlaf = Schlafdauer des Weckers, sonst das Schlafziel. Sonst `null`.
 */
private fun bedtimeAdvice(
    plan: WakePlan?,
    alarms: List<AlarmSpec>,
    extras: HomeExtras,
    now: Instant,
    zone: ZoneId,
): Pair<Instant, Instant>? {
    val values = extras.learned ?: return null
    if (plan == null || SleepNotes.nightDate(plan.windowStart, zone) != SleepNotes.nightDate(now, zone)) return null
    val alarm = alarms.firstOrNull { it.id == plan.alarmId } ?: return null
    val range =
        Learning.bedtimeRange(plan.target, alarm.sleepDurationMinutes ?: extras.sleepGoal, values.latencyMinutes)
    return range.takeIf { now.isBefore(it.second) }
}

/** Nacht-Check-Punkte, bei denen die Uhr nicht messen kann (dann keine Zeile zur automatischen Messung). */
private val WATCH_PROBLEMS = setOf(Check.WATCH, Check.WATCH_APP, Check.WATCH_SETUP)
