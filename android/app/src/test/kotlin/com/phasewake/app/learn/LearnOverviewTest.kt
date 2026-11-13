package com.phasewake.app.learn

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.phasewake.app.OfflineApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.LearnNights
import com.phasewake.app.core.LearnSkip
import com.phasewake.app.core.Learning
import com.phasewake.app.core.SleepNotes
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.ComposeTree
import com.phasewake.app.shared.DataLayer
import com.phasewake.app.sync.PhoneListenerService
import com.phasewake.app.ui.home.LearnNightsScreen
import com.phasewake.app.ui.home.learnFactsText
import com.phasewake.app.ui.home.learnReasonText
import com.phasewake.app.ui.home.learnTipText
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lern-Übersicht am Handy (r1AD 13.11.2026): Meldung der Uhr über die Warteschlange gespeichert, Nächte mit Wecker
 * gemerkt und bei «Verlauf löschen» geleert, Texte je Grund und die Liste auf dem Bildschirm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class)
class LearnOverviewTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val app get() = context.phoneApp as OfflineApp
    private val zone = ZoneId.systemDefault()

    @Before
    fun setUp() {
        ComposeTree.prepareFrames()
        runBlocking { app.learnLog.clear(Instant.now()) }
    }

    @After
    fun cleanUp() {
        ComposeTree.drain()
        app.appScope.cancel()
    }

    @Test
    fun skipFromTheWatchIsStored() = runBlocking {
        val service = Robolectric.buildService(PhoneListenerService::class.java).get()
        val target = Instant.ofEpochMilli(System.currentTimeMillis()).plus(Duration.ofHours(8))
        service.handleQueued(DataLayer.PATH_MSG_LEARN_SKIP, LearnSkip(target, LearnSkip.Reason.BATTERY, 35).encode())
        // Unlesbar oder unbekannter Grund (neuere Uhr): übergangen.
        service.handleQueued(DataLayer.PATH_MSG_LEARN_SKIP, "kaputt")
        service.handleQueued(DataLayer.PATH_MSG_LEARN_SKIP, "${target.toEpochMilli()}|neu|")
        assertEquals(listOf(LearnSkip(target, LearnSkip.Reason.BATTERY, 35)), app.learnLog.state.first().skips)
        app.learnLog.clear(Instant.now())
        assertTrue(app.learnLog.state.first().skips.isEmpty(), "«Verlauf löschen» leert die Meldungen")
    }

    @Test
    fun alarmNightsAreNotedFromNowOn() = runBlocking {
        val now = Instant.now()
        val spec = AlarmSpec(id = "w", hour = 7, minute = 0, days = DayOfWeek.entries.toSet())
        app.learnLog.noteAlarms(listOf(spec), emptySet(), now)
        val state = app.learnLog.state.first()
        val next = assertNotNull(AlarmSchedule.nextTarget(spec, now, zone))
        assertTrue(SleepNotes.nightDate(next, zone) in state.alarmNights)
        assertEquals(SleepNotes.nightDate(now, zone).plusDays(1), state.alarmsKnownFrom, "erst ab der nächsten Nacht sicher")
        // Wecker aus: die künftigen Nächte fallen heraus.
        app.learnLog.noteAlarms(listOf(spec.copy(enabled = false)), emptySet(), now)
        assertTrue(app.learnLog.state.first().alarmNights.isEmpty())
    }

    @Test
    fun everyReasonHasATextAndTheRightTip() {
        for (reason in LearnNights.Reason.entries) {
            val night = LearnNights.Night(LocalDate.of(2026, 11, 3), reason, minutes = 130, measuredEpochs = 40, pulsePercent = 1, battery = 35)
            assertTrue(learnReasonText(context, night).isNotBlank(), reason.name)
            assertEquals(reason == LearnNights.Reason.COUNTED || reason == LearnNights.Reason.LEARNING_OFF, learnTipText(context, reason) == null, reason.name)
        }
        val battery = LearnNights.Night(LocalDate.of(2026, 11, 3), LearnNights.Reason.BATTERY, battery = 35)
        assertEquals(context.getString(R.string.learn_reason_battery, 35), learnReasonText(context, battery))
        assertEquals(context.getString(R.string.learn_reason_battery_low), learnReasonText(context, battery.copy(battery = null)))
        assertEquals(context.getString(R.string.learn_tip_charge, Learning.MIN_BATTERY), learnTipText(context, LearnNights.Reason.BATTERY))
        assertNull(learnFactsText(context, battery), "ohne Aufzeichnung keine Angaben")
        assertTrue(learnFactsText(context, battery.copy(minutes = 130, measuredEpochs = 40, pulsePercent = 20))!!.contains("40"))
    }

    @Test
    fun screenListsTheNights() {
        val nights = listOf(
            LearnNights.Night(LocalDate.of(2026, 11, 4), LearnNights.Reason.BATTERY, battery = 35),
            LearnNights.Night(LocalDate.of(2026, 11, 3), LearnNights.Reason.COUNTED, minutes = 400, measuredEpochs = 160, pulsePercent = 21),
        )
        var back = false
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { PhaseWakeTheme(ThemeMode.DARK) { LearnNightsScreen(nights, onBack = { back = true }) } }
        val tree = ComposeTree(controller.get()).also { it.idle(500) }
        val texts = tree.texts().joinToString("\n")
        assertTrue(texts.contains(context.getString(R.string.learn_reason_battery, 35)), texts)
        assertTrue(texts.contains(context.getString(R.string.learn_reason_counted)), texts)
        tree.click(context.getString(R.string.onb_back))
        assertTrue(back)
        controller.pause().stop().destroy()
        // Ohne Nächte: der Hinweis.
        val empty = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        empty.get().setContent { PhaseWakeTheme(ThemeMode.LIGHT) { LearnNightsScreen(emptyList(), onBack = {}) } }
        assertTrue(ComposeTree(empty.get()).also { it.idle(500) }.shows(context.getString(R.string.learn_nights_empty)))
        empty.pause().stop().destroy()
    }
}
