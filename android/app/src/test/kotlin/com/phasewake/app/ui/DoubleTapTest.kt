package com.phasewake.app.ui

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.phasewake.app.OfflineApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.ComposeTree
import com.phasewake.app.shared.phaseWakeStore
import androidx.datastore.preferences.core.edit
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prüfrunde 2, B-1 und B-2: Zwei schnelle Tipps (echte Berührungen) dürfen nicht durch zwei Bildschirme gehen. Im
 * Editor nahm der zweite Tipp auf «Speichern» bzw. den Zurück-Pfeil auch die Startseite vom Stapel (leerer Bildschirm);
 * in der Einrichtung traf der zweite Tipp auf «Weiter» das «Später» der Lernseite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class, qualifiers = "w411dp-h891dp-port")
class DoubleTapTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var open: ActivityController<MainActivity>? = null

    /**
     * Eigener, leerer Stand: Der DataStore ist im Prozess nur einmal da und behält, was frühere Tests gesetzt haben
     * (etwa die Zustimmung zur Lernphase aus `LearningFlowTest`); im Gesamtlauf scheiterte sonst die Einrichtung.
     */
    @Before
    fun freshStore() {
        runBlocking { context.phaseWakeStore.edit { it.clear() } }
    }

    @After
    fun cleanUp() {
        open?.pause()?.stop()?.destroy()
        ComposeTree.drain()
        runBlocking { context.phoneApp.alarms.replaceAll(emptyList()) }
    }

    private fun settle(tree: ComposeTree, what: String, check: () -> Boolean) {
        val end = System.nanoTime() + 20_000_000_000L
        while (!(try { check() } catch (e: AssertionError) { false })) {
            if (System.nanoTime() > end) error("$what; Texte: ${try { tree.texts() } catch (e: AssertionError) { listOf(e.message) }}")
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            tree.idle(200)
        }
    }

    private fun rootOf(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) rootOf(view.getChildAt(i))?.let { return it }
        return null
    }

    /** Mitte des antippbaren Knotens mit Text oder Beschreibung [label]. */
    private fun centerOf(activity: MainActivity, label: String): Pair<Float, Float> {
        val root = rootOf(activity.window.decorView)!!
        root.measureAndLayoutForTest()
        val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true).first { n ->
            n.config.getOrNull(SemanticsActions.OnClick) != null && (
                n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } == label ||
                    n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
                )
        }
        return node.boundsInRoot.center.let { it.x to it.y }
    }

    /** Echter Tipp (Finger runter und hoch) an der Stelle [at]. */
    private fun tapAt(activity: MainActivity, at: Pair<Float, Float>) {
        val root = rootOf(activity.window.decorView)!!
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, at.first, at.second, 0)
            root.view.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun openEditor(): Pair<ActivityController<MainActivity>, ComposeTree> {
        ComposeTree.prepareFrames()
        val app = context.phoneApp
        val alarm = AlarmSpec(id = "work", hour = 7, minute = 0, days = setOf(DayOfWeek.MONDAY), label = "Arbeit")
        runBlocking {
            app.settings.setOnboardingDone(true)
            app.alarms.replaceAll(listOf(alarm))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        open = controller
        val tree = ComposeTree(controller.get())
        tree.idle(1_000)
        settle(tree, "Startseite zeigt den Wecker nicht") { tree.texts().any { it.contains("Arbeit") } }
        val item = tree.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && tree.text(it).contains("Arbeit") }
        item.config[SemanticsActions.OnClick].action!!.invoke()
        tree.idle(1_000)
        settle(tree, "Editor nicht offen") { tree.shows(context.getString(R.string.editor_edit)) }
        return controller to tree
    }

    /** Zwei Tipps auf [label] im Abstand [gapMs]: danach steht die Startseite mit dem Wecker da, die App bleibt offen. */
    private fun doubleTapInEditor(label: String, gapMs: Long) {
        val (controller, tree) = openEditor()
        val at = centerOf(controller.get(), label)
        tapAt(controller.get(), at)
        tree.idle(gapMs)
        tapAt(controller.get(), at)
        tree.idle(3_000)
        val texts = tree.texts()
        assertTrue(texts.any { it.contains("Arbeit") }, "Nach zwei Tipps auf «$label» ($gapMs ms) ist die Startseite leer: $texts")
        assertTrue(tree.shows(context.getString(R.string.nav_history)), "Untere Leiste fehlt: $texts")
        assertFalse(controller.get().isFinishing)
    }

    @Test
    fun doubleTapSaveAt16ms() = doubleTapInEditor(context.getString(R.string.editor_save), 16)

    @Test
    fun doubleTapSaveAt0ms() = doubleTapInEditor(context.getString(R.string.editor_save), 0)

    @Test
    fun doubleTapBackArrowAt33ms() = doubleTapInEditor(context.getString(R.string.action_cancel), 33)

    /** Einrichtung: zwei Tipps auf «Weiter» der Seite «Erster Wecker» (150 ms): Die Lernseite bleibt, nichts abgelehnt. */
    @Test
    fun onboardingDoubleTapNextKeepsLearningQuestion() {
        ComposeTree.prepareFrames()
        val app = context.phoneApp
        runBlocking {
            app.alarms.replaceAll(emptyList())
            app.settings.setOnboardingDone(false)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        open = controller
        val tree = ComposeTree(controller.get())
        tree.idle(2_000)
        val next = context.getString(R.string.onb_next)
        settle(tree, "Einrichtung nicht offen") { tree.shows(next) }
        repeat(3) {
            tree.click(next)
            tree.idle(800)
        }
        settle(tree, "Seite «Erster Wecker» nicht offen") { tree.shows(context.getString(R.string.onb_alarm_title)) }
        val consentBefore = runBlocking { app.learning.state.first().consent }
        val at = centerOf(controller.get(), next)
        tapAt(controller.get(), at)
        tree.idle(150)
        tapAt(controller.get(), at)
        tree.idle(1_500)
        assertEquals(consentBefore, runBlocking { app.learning.state.first().consent }, "Zweiter Tipp hat die Lernphase beantwortet")
        assertNull(consentBefore)
        assertTrue(tree.shows(context.getString(R.string.learn_consent_start)), "Lernseite übersprungen: ${tree.texts()}")
        assertEquals(1, runBlocking { app.alarms.current().alarms.size })
    }
}
