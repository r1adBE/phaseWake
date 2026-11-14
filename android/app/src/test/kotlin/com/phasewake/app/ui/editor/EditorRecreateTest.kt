package com.phasewake.app.ui.editor

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.phasewake.app.OfflineApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.ComposeTree
import com.phasewake.app.ui.MainActivity
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Editor eines bestehenden Weckers, Activity neu aufgebaut (Drehen, Hell/Dunkel, Schriftgrösse), dann «Speichern»:
 * derselbe Wecker wird geändert, kein zweiter angelegt, und die Eingaben von vor dem Neuaufbau bleiben.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class, qualifiers = "w411dp-h891dp-port")
class EditorRecreateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var open: ActivityController<MainActivity>? = null

    /** Bildschirm zerstören und offene Arbeit von Compose erledigen, sonst zeichnen spätere Tests nicht mehr ([ComposeTree.drain]). */
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

    @Test
    fun saveAfterRecreateEditsTheSameAlarmAndKeepsInputs() {
        ComposeTree.prepareFrames()
        val app = context.phoneApp
        val alarm = AlarmSpec(id = "work", hour = 7, minute = 0, days = setOf(DayOfWeek.MONDAY), label = "Arbeit")
        runBlocking {
            app.settings.setOnboardingDone(true)
            // Nur dieser Wecker (die App ist prozessweit, frühere Tests können Wecker hinterlassen).
            app.alarms.replaceAll(listOf(alarm))
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        open = controller
        var tree = ComposeTree(controller.get())
        tree.idle(1_000)
        settle(tree, "Startseite zeigt den Wecker nicht") { tree.texts().any { it.contains("Arbeit") } }
        val item = tree.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && tree.text(it).contains("Arbeit") }
        item.config[SemanticsActions.OnClick].action!!.invoke()
        tree.idle(1_000)
        val edit = context.getString(R.string.editor_edit)
        settle(tree, "Editor nicht offen") { tree.shows(edit) }
        // Eine Eingabe vor dem Neuaufbau: Einschlaf-Modus.
        tree.click(context.getString(R.string.editor_mode_sleep))

        // Neuaufbau wie beim Drehen: Zustand sichern, Activity zerstören, mit dem Zustand neu aufbauen.
        val saved = Bundle()
        controller.saveInstanceState(saved)
        controller.pause().stop().destroy()
        ComposeTree.drain()
        val second = Robolectric.buildActivity(MainActivity::class.java).setup(saved)
        open = second
        tree = ComposeTree(second.get())
        tree.idle(1_000)
        settle(tree, "Editor nach Neuaufbau nicht offen") { tree.shows(edit) }
        tree.click(context.getString(R.string.editor_save))
        // Speichern läuft im Hintergrund.
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end && runBlocking { app.alarms.current().alarms.none { it.sleepDurationMinutes != null } }) {
            Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
        }
        val list = runBlocking { app.alarms.current().alarms }
        assertEquals(listOf("work"), list.map { it.id }, "Speichern nach Neuaufbau hat einen zweiten Wecker angelegt")
        val saved1 = list.single()
        assertEquals(setOf(DayOfWeek.MONDAY), saved1.days)
        assertEquals("Arbeit", saved1.label)
        assertEquals(450, saved1.sleepDurationMinutes, "Eingabe vor dem Neuaufbau verloren")
    }
}
