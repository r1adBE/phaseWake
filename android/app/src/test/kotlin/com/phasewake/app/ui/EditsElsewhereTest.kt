package com.phasewake.app.ui

import android.content.Context
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.phasewake.app.OfflineApp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.Transient
import com.phasewake.app.core.TransientKind
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.ComposeTree
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prüfrunde 2, Handy: Änderungen anderswo, während ein Bildschirm offen ist (B-3, B-11), und Rückfragen vor dem
 * Löschen einer eigenen Notiz (B-10) und bevor der Probewecker einen wartenden Powernap ersetzt (B-12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class, qualifiers = "w411dp-h891dp-port")
class EditsElsewhereTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var open: ActivityController<MainActivity>? = null

    @After
    fun cleanUp() {
        open?.pause()?.stop()?.destroy()
        ComposeTree.drain()
        runBlocking {
            context.phoneApp.alarms.replaceAll(emptyList())
            context.phoneApp.transient.clear()
        }
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

    private fun start(alarms: List<AlarmSpec>): ComposeTree {
        ComposeTree.prepareFrames()
        val app = context.phoneApp
        runBlocking {
            app.settings.setOnboardingDone(true)
            app.alarms.replaceAll(alarms)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        open = controller
        val tree = ComposeTree(controller.get())
        tree.idle(1_000)
        return tree
    }

    private fun alarm(id: String, label: String = "") = AlarmSpec(id = id, hour = 7, minute = 0, days = setOf(DayOfWeek.MONDAY), label = label)

    /** B-3: Der bearbeitete Wecker wird anderswo gelöscht: Hinweis, Editor zu, kein neuer Wecker aus den Eingaben. */
    @Test
    fun alarmDeletedWhileEditingClosesEditor() {
        val tree = start(listOf(alarm("work", "Arbeit")))
        settle(tree, "Startseite zeigt den Wecker nicht") { tree.texts().any { it.contains("Arbeit") } }
        val item = tree.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && tree.text(it).contains("Arbeit") }
        item.config[SemanticsActions.OnClick].action!!.invoke()
        tree.idle(1_000)
        val edit = context.getString(R.string.editor_edit)
        settle(tree, "Editor nicht offen") { tree.shows(edit) }
        tree.click(context.getString(R.string.editor_mode_sleep))
        runBlocking { context.phoneApp.alarms.remove("work") }
        settle(tree, "Editor bleibt offen, obwohl der Wecker gelöscht ist") {
            !tree.shows(edit) && !tree.shows(context.getString(R.string.editor_new)) && tree.shows(context.getString(R.string.nav_history))
        }
        tree.idle(1_000)
        assertEquals(context.getString(R.string.editor_alarm_gone), ShadowToast.getTextOfLatestToast())
        assertTrue(runBlocking { context.phoneApp.alarms.current().alarms }.isEmpty())
    }

    /** B-11: Während des Anlegens sind anderswo alle acht Plätze belegt worden: Hinweis, der Editor bleibt mit den Eingaben offen. */
    @Test
    fun ninthAlarmShowsHintAndKeepsEditor() {
        // Ohne Wecker: «Neuer Wecker» steht auf der Startseite (mit Weckern ist es der schwebende Knopf).
        val tree = start(emptyList())
        val add = context.getString(R.string.home_add_alarm)
        settle(tree, "«Neuer Wecker» fehlt") { tree.shows(add) }
        tree.click(add)
        tree.idle(1_000)
        val title = context.getString(R.string.editor_new)
        settle(tree, "Editor nicht offen") { tree.shows(title) }
        runBlocking { context.phoneApp.alarms.replaceAll((1..8).map { alarm("a$it") }) }
        tree.idle(1_000)
        tree.click(context.getString(R.string.editor_save))
        tree.idle(1_500)
        assertTrue(tree.shows(title), "Editor geschlossen: ${tree.texts()}")
        assertEquals(context.getString(R.string.home_max_alarms, 8), ShadowToast.getTextOfLatestToast())
        assertEquals(8, runBlocking { context.phoneApp.alarms.current().alarms.size })
    }

    private fun openSettings(tree: ComposeTree) {
        tree.click(context.getString(R.string.nav_settings))
        tree.idle(1_000)
        settle(tree, "Einstellungen nicht offen") { tree.shows(context.getString(R.string.settings_data)) }
    }

    /** B-10: Löschen einer eigenen Notiz fragt zuerst; erst «Löschen» entfernt sie. */
    @Test
    fun deletingCustomNoteAsksFirst() {
        runBlocking { context.phoneApp.notes.addCustom("Meditation") }
        val tree = start(emptyList())
        openSettings(tree)
        val delete = context.getString(R.string.notes_custom_delete, "Meditation")
        settle(tree, "Notiz fehlt") { tree.shows("Meditation") }
        tree.click(delete)
        tree.idle(500)
        assertEquals(listOf("Meditation"), runBlocking { context.phoneApp.notes.custom.first() }.map { it.name })
        assertTrue(DialogNodes.texts().contains(delete), "Keine Rückfrage: ${DialogNodes.texts()}")
        DialogNodes.click(context.getString(R.string.settings_clear_history_confirm))
        tree.idle(500)
        val end = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < end && runBlocking { context.phoneApp.notes.custom.first() }.isNotEmpty()) {
            Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(runBlocking { context.phoneApp.notes.custom.first() }.isEmpty())
    }

    /** B-12: «In 2 Minuten testen» mit wartendem Powernap fragt zuerst; der Powernap bleibt bis zur Bestätigung. */
    @Test
    fun testAlarmAsksBeforeReplacingNap() {
        val nap = Transient.nap(null, Instant.now(), ZoneId.systemDefault(), 20, "nap1")
        runBlocking { context.phoneApp.transient.set(nap) }
        val tree = start(emptyList())
        openSettings(tree)
        tree.click(context.getString(R.string.test_button))
        tree.idle(1_000)
        assertTrue(DialogNodes.texts().contains(context.getString(R.string.test_replaces_nap)), "Keine Rückfrage: ${DialogNodes.texts()}")
        assertEquals(TransientKind.NAP, runBlocking { context.phoneApp.transient.active() }?.kind)
        DialogNodes.click(context.getString(R.string.action_cancel))
        tree.idle(500)
        assertFalse(DialogNodes.texts().contains(context.getString(R.string.test_replaces_nap)))
        assertEquals(TransientKind.NAP, runBlocking { context.phoneApp.transient.active() }?.kind)
    }

    /** B-13: Die Rückfrage «Verlauf löschen» nennt auch Notizen, Befinden, Haken und Geräusche (wie das Löschen). */
    @Test
    fun clearHistoryNamesEverything() {
        val tree = start(emptyList())
        openSettings(tree)
        tree.click(context.getString(R.string.settings_clear_history))
        tree.idle(500)
        val text = DialogNodes.texts().joinToString(" ")
        assertTrue(text.contains(context.getString(R.string.settings_clear_history_also)), text)
        assertTrue(text.contains(context.getString(R.string.settings_clear_history_learn)), text)
        DialogNodes.click(context.getString(R.string.action_cancel))
    }
}
