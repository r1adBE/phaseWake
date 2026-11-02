package com.phasewake.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.edit
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.core.WakePlan
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.MainActivity
import com.phasewake.app.ui.history.feelingName
import com.phasewake.app.ui.history.wakeTimingText
import com.phasewake.app.ui.home.Check
import com.phasewake.app.ui.home.MORNING_FIRST_UNTIL_HOUR
import com.phasewake.app.ui.home.isMorningOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import kotlinx.coroutines.launch

/**
 * Widget «Nächster Wecker» (klassisch mit RemoteViews, ohne zusätzliche Bibliothek): Weckfenster, Tag und
 * Nacht-Check (✓ bzw. ! mit der ersten offenen Prüfung). Antippen öffnet die App. Aktualisiert alle 30 Minuten
 * (System) und bei jedem Neuplanen des Zweitweckers und jedem Nacht-Check. Fenster und Check zeigt auch die
 * Mitteilung [NextAlarmNotice] auf dem Sperrbildschirm.
 *
 * «Details von heute»: Vom Wecken bis 12 Uhr (Ortszeit, wie der Morgenbericht) zeigt es statt des nächsten
 * Weckers die letzte Nacht: «Geweckt um 6:42», Zeitpunkt und Grund, Schlafdauer laut Health Connect und Befinden
 * (je falls bekannt). Neu gezeichnet, sobald eine Nacht gespeichert oder das Befinden gesetzt wird
 * ([PhaseWakeApp][com.phasewake.app.PhaseWakeApp]), die App eine neue Schlafdauer ablegt ([saveAsleep]) und um
 * 12 Uhr ([scheduleNoon]).
 */
class NextAlarmWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        // Prüfung hier, damit Lint die Liste der erlaubten Aktionen sieht.
        if (intent.action !in ACTIONS) return
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE && BackupAlarm.isUnlocked(context)) {
            val pending = goAsync()
            context.phoneApp.appScope.launch {
                try {
                    refresh(context)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    /** Aktualisieren und Merken des Nacht-Checks; von überall aufrufbar. */
    companion object {
        private val ACTIONS = setOf(
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED,
        )
        private const val PREFS = "widget"
        private const val KEY_CHECK = "check"
        private const val KEY_ASLEEP_NIGHT = "asleepNight"
        private const val KEY_ASLEEP_MINUTES = "asleepMinutes"

        /** Kennung des Auftrags «um 12 Uhr neu zeichnen». */
        private const val REQUEST_NOON = 12

        /** So viel später darf das System den Auftrag um 12 Uhr ausführen. */
        private const val NOON_WINDOW_MS = 60_000L

        /** Zeichnet alle Widgets neu (im Hintergrund); ohne Widget oder vor dem ersten Entsperren nichts. */
        fun update(context: Context) {
            if (!BackupAlarm.isUnlocked(context) || ids(context).isEmpty()) return
            context.phoneApp.appScope.launch { refresh(context) }
        }

        /**
         * Merkt das Ergebnis des Nacht-Checks für das Widget (offene Prüfungen, leer = bereit) und zeichnet neu.
         * Der Check selbst läuft nur, solange die App offen ist.
         */
        fun saveCheck(context: Context, failed: List<Check>) {
            val text = failed.joinToString(",") { it.name }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_CHECK, null) == text) return
            prefs.edit { putString(KEY_CHECK, text) }
            update(context)
            NextAlarmNotice.update(context)
        }

        /**
         * Merkt die Schlafdauer der jüngsten eigenen Nacht für das Widget: [night] ist ihr Nachtdatum
         * ([SleepNotes.nightDate]), [minutes] der Wert aus [NoteEffect.asleepByNight][com.phasewake.app.core.NoteEffect.asleepByNight]
         * wie in Verlauf und Detailseite; `null` = keine Daten (dann ohne Dauer). Health Connect liest Schlafdaten
         * nur bei offener App, darum legt die App den Wert nach jedem Laden hier ab; das Widget liest nur.
         */
        fun saveAsleep(context: Context, night: LocalDate, minutes: Long?) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val text = night.toString()
            if (prefs.getString(KEY_ASLEEP_NIGHT, null) == text && prefs.getLong(KEY_ASLEEP_MINUTES, -1L) == (minutes ?: -1L)) return
            prefs.edit {
                putString(KEY_ASLEEP_NIGHT, text)
                if (minutes == null) remove(KEY_ASLEEP_MINUTES) else putLong(KEY_ASLEEP_MINUTES, minutes)
            }
            update(context)
        }

        /** Gemerkte Schlafdauer der Nacht mit dem Nachtdatum [night]; `null` ohne Wert für genau diese Nacht. */
        private fun asleepMinutes(context: Context, night: LocalDate): Long? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_ASLEEP_NIGHT, null) != night.toString()) return null
            return prefs.getLong(KEY_ASLEEP_MINUTES, -1L).takeIf { it > 0 }
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NextAlarmWidget::class.java))

        private suspend fun refresh(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val app = context.phoneApp
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val views = RemoteViews(context.packageName, R.layout.widget_next_alarm)
            val night = app.nights.load().maxByOrNull { it.wokeAt }?.takeIf { isMorningOf(it, now, zone) }
            if (night != null) {
                showNight(context, views, night, asleepMinutes(context, SleepNotes.nightDate(night, zone)))
                scheduleNoon(context, night, zone)
            } else {
                showNextAlarm(context, views, now, zone)
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /** Nächster Wecker: Fenster, Tag und Name, Nacht-Check. */
        private suspend fun showNextAlarm(context: Context, views: RemoteViews, now: Instant, zone: ZoneId) {
            val app = context.phoneApp
            val alarms = app.alarms.current().alarms
            // Ohne die schon erledigte Nacht (früh geweckt): sonst stünde bis zur Zielzeit noch der heutige Wecker da.
            val plan = AlarmSchedule.earliestPlan(alarms, now, zone, app.holidays.store.current(), app.backup.doneUntil())
            if (plan == null) {
                views.setTextViewText(R.id.widget_window, context.getString(R.string.widget_no_alarm))
                views.setViewVisibility(R.id.widget_day, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_window, windowText(context, plan, zone))
                val label = alarms.firstOrNull { it.id == plan.alarmId }?.label.orEmpty()
                views.setTextViewText(R.id.widget_day, listOf(day(context, plan.target, zone), label).filter { it.isNotBlank() }.joinToString(" · "))
                views.setViewVisibility(R.id.widget_day, View.VISIBLE)
            }
            val check = checkText(context)
            views.setTextViewText(R.id.widget_check, check.orEmpty())
            views.setViewVisibility(R.id.widget_check, if (check == null || plan == null) View.GONE else View.VISIBLE)
        }

        /**
         * Letzte Nacht: Titel «Letzte Nacht», gross «Geweckt um 6:42», darunter Zeitpunkt und Grund wie im
         * Morgenbericht, dann Schlafdauer ([asleep], Minuten laut Health Connect) und Befinden in einer Zeile,
         * z. B. «7 h 41 min · Befinden: Gut»; was fehlt, entfällt (ohne beides keine Zeile).
         */
        private fun showNight(context: Context, views: RemoteViews, night: NightRecord, asleep: Long?) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.home_last_night))
            views.setTextViewText(R.id.widget_window, context.getString(R.string.night_woke, Format.time(night.wokeAt)))
            val timing = wakeTimingText(context, night.minutesBeforeTarget.toInt(), night.minutesAfterTarget.toInt(), night.reason)
            views.setTextViewText(R.id.widget_day, timing)
            views.setViewVisibility(R.id.widget_day, View.VISIBLE)
            val feeling = night.feeling?.let { context.getString(R.string.feeling_value, feelingName(context, it)) }
            val line = listOfNotNull(asleep?.let { Format.duration(context, Duration.ofMinutes(it)) }, feeling).joinToString(" · ")
            views.setTextViewText(R.id.widget_check, line)
            views.setViewVisibility(R.id.widget_check, if (line.isEmpty()) View.GONE else View.VISIBLE)
        }

        /**
         * Um 12 Uhr des Weckmorgens neu zeichnen, damit wieder der nächste Wecker erscheint. Ungenauer Wecker
         * mit kurzem Fenster (ohne Berechtigung für genaue Wecker); ein neuer Auftrag ersetzt den alten.
         */
        private fun scheduleNoon(context: Context, night: NightRecord, zone: ZoneId) {
            val noon = night.wokeAt.atZone(zone).toLocalDate().atTime(MORNING_FIRST_UNTIL_HOUR, 0).atZone(zone).toInstant()
            val intent = Intent(context, NextAlarmWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            val pending = PendingIntent.getBroadcast(context, REQUEST_NOON, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(AlarmManager::class.java)?.setWindow(AlarmManager.RTC, noon.toEpochMilli(), NOON_WINDOW_MS, pending)
        }

        /** Weckfenster «6:30 – 7:00», bei «Genau» «Genau um 7:00» (auch für die Mitteilung [NextAlarmNotice]). */
        internal fun windowText(context: Context, plan: WakePlan, zone: ZoneId): String = if (plan.exact) {
            context.getString(R.string.window_exact_line, Format.time(plan.target, zone))
        } else {
            Format.time(plan.windowStart, zone) + " – " + Format.time(plan.target, zone)
        }

        /** «✓ Bereit für die Nacht» oder «! » mit der ersten offenen Prüfung; `null`, solange kein Check lief. */
        internal fun checkText(context: Context): String? {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHECK, null) ?: return null
            val first = stored.split(",").firstNotNullOfOrNull { name -> Check.entries.firstOrNull { it.name == name } }
            return if (first == null) "✓ " + context.getString(R.string.check_ready) else "! " + context.getString(first.problemTitle)
        }

        /** «Heute», «Morgen» oder der Wochentag. */
        internal fun day(context: Context, target: Instant, zone: ZoneId): String {
            val date = target.atZone(zone).toLocalDate()
            val today = LocalDate.now(zone)
            val locale = context.resources.configuration.locales[0]
            return when (date) {
                today -> context.getString(R.string.home_next_today)
                today.plusDays(1) -> context.getString(R.string.home_next_tomorrow)
                else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
            }
        }
    }
}
