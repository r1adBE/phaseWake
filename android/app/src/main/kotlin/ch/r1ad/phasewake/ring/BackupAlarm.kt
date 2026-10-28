package ch.r1ad.phasewake.ring

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import ch.r1ad.phasewake.core.AlarmSchedule
import ch.r1ad.phasewake.phoneApp
import ch.r1ad.phasewake.shared.AlarmRepository
import ch.r1ad.phasewake.ui.MainActivity
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Zweitwecker auf dem Handy. Normalerweise meldet die Uhr den Weckmoment und das Handy klingelt mit.
 * Kommt bis kurz nach der Zielzeit nichts von der Uhr (Akku leer, nicht getragen, nicht verbunden),
 * klingelt das Handy selbst. Gilt nur für Wecker mit eingeschaltetem Zweitwecker.
 */
class BackupAlarm(
    private val context: Context,
    private val alarms: AlarmRepository,
    private val store: DataStore<Preferences>,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    suspend fun reschedule() {
        alarmManager.cancel(pending())
        val backups = alarms.current().alarms.filter { it.phoneBackup }
        // Hat die Uhr die laufende Nacht übernommen, gilt erst der nächste Wecker danach.
        val suppressed = Instant.ofEpochMilli(store.data.first()[SUPPRESSED_UNTIL] ?: 0L)
        val from = maxOf(clock.instant(), suppressed)
        val plan = AlarmSchedule.earliestPlan(backups, from, clock.zone) ?: return
        val at = plan.target.plus(GRACE)
        val show = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(), show), pending(plan.alarmId))
    }

    /** Die Uhr weckt gerade: Zweitwecker für diese Nacht aussetzen. */
    suspend fun watchIsRinging() {
        val until = clock.instant().plus(Duration.ofMinutes(ch.r1ad.phasewake.core.AlarmSpec.MAX_WINDOW_MINUTES.toLong())).plus(GRACE)
        store.edit { it[SUPPRESSED_UNTIL] = until.toEpochMilli() }
        reschedule()
    }

    private fun pending(alarmId: String? = null): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION)
        if (alarmId != null) intent.putExtra(PhoneRingService.EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Löst den Zweitwecker aus und plant den nächsten. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION -> PhoneRingService.ring(context, intent.getStringExtra(PhoneRingService.EXTRA_ALARM_ID))
                else -> Unit // Neustart, Update, Zeitwechsel: nur neu planen.
            }
            val pending = goAsync()
            val app = context.phoneApp
            app.appScope.launch {
                try {
                    app.backup.reschedule()
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private companion object {
        const val ACTION = "ch.r1ad.phasewake.action.BACKUP_RING"
        val SUPPRESSED_UNTIL = longPreferencesKey("backup_suppressed_until")

        /** So lange nach der Zielzeit wartet das Handy auf die Uhr. */
        val GRACE: Duration = Duration.ofMinutes(2)
    }
}
