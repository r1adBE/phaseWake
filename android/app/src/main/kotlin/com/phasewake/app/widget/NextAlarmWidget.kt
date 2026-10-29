package com.phasewake.app.widget

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
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.BackupAlarm
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.MainActivity
import com.phasewake.app.ui.home.Check
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import kotlinx.coroutines.launch

/**
 * Widget «Nächster Wecker» (klassisch mit RemoteViews, ohne zusätzliche Bibliothek): Weckfenster, Tag und
 * Nacht-Check (✓ bzw. ! mit der ersten offenen Prüfung). Antippen öffnet die App. Aktualisiert alle 30 Minuten
 * (System) und bei jedem Neuplanen des Zweitweckers und jedem Nacht-Check.
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
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, NextAlarmWidget::class.java))

        private suspend fun refresh(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val app = context.phoneApp
            val zone = ZoneId.systemDefault()
            val now = Instant.now()
            val alarms = app.alarms.current().alarms
            // Ohne die schon erledigte Nacht (früh geweckt): sonst stünde bis zur Zielzeit noch der heutige Wecker da.
            val plan = AlarmSchedule.earliestPlan(alarms, now, zone, app.holidays.store.current(), app.backup.doneUntil())
            val views = RemoteViews(context.packageName, R.layout.widget_next_alarm)
            if (plan == null) {
                views.setTextViewText(R.id.widget_window, context.getString(R.string.widget_no_alarm))
                views.setViewVisibility(R.id.widget_day, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_window, Format.time(plan.windowStart, zone) + " – " + Format.time(plan.target, zone))
                val label = alarms.firstOrNull { it.id == plan.alarmId }?.label.orEmpty()
                views.setTextViewText(R.id.widget_day, listOf(day(context, plan.target, zone), label).filter { it.isNotBlank() }.joinToString(" · "))
                views.setViewVisibility(R.id.widget_day, View.VISIBLE)
            }
            val check = checkText(context)
            views.setTextViewText(R.id.widget_check, check.orEmpty())
            views.setViewVisibility(R.id.widget_check, if (check == null || plan == null) View.GONE else View.VISIBLE)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        /** «✓ Bereit für die Nacht» oder «! » mit der ersten offenen Prüfung; `null`, solange kein Check lief. */
        private fun checkText(context: Context): String? {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHECK, null) ?: return null
            val first = stored.split(",").firstNotNullOfOrNull { name -> Check.entries.firstOrNull { it.name == name } }
            return if (first == null) "✓ " + context.getString(R.string.check_ready) else "! " + context.getString(first.problemTitle)
        }

        /** «Heute», «Morgen» oder der Wochentag. */
        private fun day(context: Context, target: Instant, zone: ZoneId): String {
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
