package com.phasewake.app.widget

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.phasewake.app.QuietApp
import com.phasewake.app.R
import com.phasewake.app.core.WakePlan
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mitteilung «Nächster Wecker» ohne Recht für exakte Wecker (Android 12/12L): Warnung als Titel, Weckfenster als Text
 * und «Erlauben» zur Systemseite. Mit Recht (ab Android 13 immer, USE_EXACT_ALARM) die gewohnte Mitteilung.
 *
 * Auf SDK 31 (Android 12, Abbild in gradle/robolectric.gradle.kts): Der Schatten entzieht oder gibt das Recht über
 * [android.app.AlarmManager.canScheduleExactAlarms] wie in den Einstellungen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = NextAlarmNoticeExactTest.Android12App::class)
class NextAlarmNoticeExactTest {
    /**
     * Unter Android 13 verlangt androidx.core für nicht exportierte Empfänger (Akku in der Nacht, beim Start der App)
     * das eigene Recht `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Auf dem Gerät gibt es das zusammengeführte Manifest
     * (Signatur), Robolectric gewährt es nicht von selbst: hier vor dem Start der App wie auf dem Gerät.
     */
    class Android12App : QuietApp() {
        override fun onCreate() {
            shadowOf(this).grantPermissions("$packageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            super.onCreate()
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-14T22:30:00Z")
    private val plan = WakePlan("a", Instant.parse("2026-11-15T06:00:00Z"), Instant.parse("2026-11-15T06:30:00Z"))

    private fun build(): Pair<NextAlarmNotice.Content, Notification> {
        val content = NextAlarmNotice.content(context, plan, zone)
        return content to NextAlarmNotice(context) { now }.build(plan, now, content)
    }

    @Test
    fun withoutExactAlarmsTheNoticeWarnsAndOffersTheSettings() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val (content, notification) = build()
        assertTrue(content.exactMissing)
        assertEquals(context.getString(R.string.notice_exact_alarms_missing), content.title)
        assertEquals(NextAlarmWidget.windowText(context, plan, zone), content.text)
        assertEquals(content.title, NotificationCompat.getContentTitle(notification))
        val action = notification.actions.single()
        assertEquals(context.getString(R.string.check_allow), action.title)
        val pending = shadowOf(action.actionIntent)
        assertTrue(pending.isActivity)
        assertTrue(pending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pending.savedIntent.action)
        assertEquals("package:${context.packageName}", pending.savedIntent.dataString)
        // Sperrbildschirm wie bisher: privat, öffentlich nur «Nächster Wecker».
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        val public = assertNotNull(notification.publicVersion)
        assertEquals(context.getString(R.string.widget_name), NotificationCompat.getContentTitle(public))
        assertEquals(Duration.ofMinutes(8 * 60 + 30).toMillis(), notification.timeoutAfter)
    }

    @Test
    fun withExactAlarmsTheNoticeShowsTheWindowWithoutAction() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val (content, notification) = build()
        assertFalse(content.exactMissing)
        assertEquals(NextAlarmWidget.windowText(context, plan, zone), NotificationCompat.getContentTitle(notification))
        assertTrue(notification.actions.isNullOrEmpty())
    }
}
