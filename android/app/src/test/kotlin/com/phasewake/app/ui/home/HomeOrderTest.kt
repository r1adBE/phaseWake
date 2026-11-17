package com.phasewake.app.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.phasewake.app.OfflineApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Finding
import com.phasewake.app.core.FindingKind
import com.phasewake.app.core.Learning
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.ComposeTree
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reihenfolge der Startseite: «Deine Wecker» gleich nach dem Nacht-Check, dann
 * Powernap, dann die Abendkarten – wie am iPhone. Neben der Bettzeit-Karte keine Auffälligkeit mit demselben Tipp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class)
class HomeOrderTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val work = AlarmSpec(id = "work", hour = 7, minute = 0, days = DayOfWeek.entries.toSet())
    private val short = Finding(FindingKind.SHORT_NIGHTS, 3)
    private val deep = Finding(FindingKind.LOW_DEEP, 11)

    @Before
    fun setUp() = ComposeTree.prepareFrames()

    @After
    fun cleanUp() {
        ComposeTree.drain()
        context.phoneApp.appScope.cancel()
    }

    private fun at(hour: Int): Instant = ZonedDateTime.of(2026, 11, 17, hour, 0, 0, 0, zone).toInstant()

    /** Startseite um [now] mit Nacht-Check, Powernap und gelernten Werten (Bettzeit-Karte am Abend). */
    private fun show(now: Instant, block: (ComposeTree) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            PhaseWakeTheme(ThemeMode.LIGHT) {
                HomeScreen(
                    HomeState(
                        listOf(work), lastNight = null, check = NightCheckState(emptyList()), finding = short,
                        bedtimeFinding = deep,
                    ),
                    HomeActions(onFix = {}, onAdd = {}, onEdit = {}, onToggle = { _, _ -> }),
                    contentPadding = PaddingValues(),
                    clock = { now },
                    extras = HomeExtras(onNap = {}, learned = Learning.Values(null, null, 15, 7)),
                )
            }
        }
        block(ComposeTree(controller.get()).also { it.idle(500) })
        controller.pause().stop().destroy()
    }

    /** Stelle des Eintrags [key] in der Liste der Startseite; -1 = fehlt. */
    private fun ComposeTree.index(key: Any): Int {
        val list =
            nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.IndexForKey) != null } ?: fail("keine Liste")
        return list.config[SemanticsProperties.IndexForKey](key)
    }

    @Test
    fun alarmsComeRightAfterTheNightCheckBeforeTheEveningCards() = show(at(21)) { tree ->
        val order = listOf("check", "header", "work", "nap", "bedtimeAdvice", "notes", "finding").map { tree.index(it) }
        assertTrue(order.none { it < 0 }, "alle Karten da: $order")
        assertEquals(order.sorted(), order, "Reihenfolge Nacht-Check, Wecker, Powernap, Abendkarten, Auffälligkeit")
        assertEquals(tree.index("check") + 1, tree.index("header"), "«Deine Wecker» direkt nach dem Nacht-Check")
    }

    @Test
    fun bedtimeCardHidesTheFindingWithTheSameTip() {
        show(at(21)) { tree ->
            tree.scrollToKey("finding")
            val texts = tree.texts().joinToString("\n")
            assertTrue(texts.contains(findingText(context, deep)), texts)
            assertFalse(texts.contains(findingText(context, short)), texts)
        }
        // Tagsüber keine Bettzeit-Karte: die wichtigste Auffälligkeit wie bisher.
        show(at(14)) { tree ->
            assertEquals(-1, tree.index("bedtimeAdvice"))
            tree.scrollToKey("finding")
            assertTrue(tree.texts().joinToString("\n").contains(findingText(context, short)))
        }
    }
}
