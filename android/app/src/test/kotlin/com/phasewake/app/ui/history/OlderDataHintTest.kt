package com.phasewake.app.ui.history

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import com.phasewake.app.MemoryStore
import com.phasewake.app.QuietApp
import com.phasewake.app.R
import com.phasewake.app.SettingsRepository
import com.phasewake.app.phoneApp
import com.phasewake.app.ring.ComposeTree
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Einmaliger Hinweis im Verlauf «Ältere Daten aus … freigeben» ([OlderDataHint]): nur solange das Recht fehlt und er
 * nicht beantwortet ist; «Freigeben» und «Nicht jetzt» merken die Antwort (dauerhaft), «Freigeben» fragt danach an.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class OlderDataHintTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val log = mutableListOf<String>()

    @Before
    fun setUp() = ComposeTree.prepareFrames()

    @After
    fun cleanUp() {
        ComposeTree.drain()
        context.phoneApp.appScope.cancel()
    }

    private fun hint(missing: Boolean, answered: Boolean, source: String? = "Samsung Health") =
        OlderDataHint.of(missing, answered, source, onAllow = { log += "anfragen" }) { log += "gemerkt" }

    @Test
    fun showsOnlyWhileMissingAndUnanswered() {
        assertNotNull(hint(missing = true, answered = false))
        assertNull(hint(missing = false, answered = false), "Recht erteilt, nicht verbunden oder Funktion unbekannt")
        assertNull(hint(missing = true, answered = true), "schon beantwortet")
        assertNull(hint(missing = false, answered = true))
    }

    @Test
    fun bothButtonsRememberTheAnswerAllowAlsoAsks() {
        hint(missing = true, answered = false)!!.onLater()
        assertEquals(listOf("gemerkt"), log)
        log.clear()
        hint(missing = true, answered = false)!!.onAllow()
        assertEquals(listOf("gemerkt", "anfragen"), log, "zuerst gemerkt: auch ohne Erteilung nie wieder")
    }

    /** Quelle wie der Hinweis unter der Liste: ein Name, wenn alle Nächte aus derselben App kommen, sonst allgemein. */
    @Test
    fun sourceIsTheSingleKnownName() {
        assertEquals("Samsung Health", OlderDataHint.source(listOf("Samsung Health", "Samsung Health")))
        assertNull(OlderDataHint.source(listOf("Samsung Health", "Fitbit")))
        assertNull(OlderDataHint.source(listOf("Samsung Health", null)))
        assertNull(OlderDataHint.source(emptyList()))
    }

    /** Die Antwort bleibt gespeichert: Nach «Nicht jetzt» oder «Freigeben» erscheint der Hinweis nie wieder. */
    @Test
    fun answerIsStored() = runBlocking {
        val store = MemoryStore()
        assertFalse(SettingsRepository(store).olderDataAnswered.first())
        SettingsRepository(store).setOlderDataAnswered()
        assertTrue(SettingsRepository(store).olderDataAnswered.first())
    }

    /** Im Verlauf: eine Zeile mit der Quelle, «Nicht jetzt» und «Freigeben»; ohne Hinweis keine Karte. */
    @Test
    fun historyShowsTheCardAndItsButtons() {
        val title = context.getString(R.string.history_older_title, "Samsung Health")
        show(hint(missing = true, answered = false)) { tree ->
            assertTrue(tree.shows(title), tree.texts().toString())
            tree.click(context.getString(R.string.history_older_later))
            assertEquals(listOf("gemerkt"), log)
            tree.click(context.getString(R.string.history_older_allow))
            assertEquals(listOf("gemerkt", "gemerkt", "anfragen"), log)
        }
        show(hint(missing = true, answered = false, source = null)) { tree ->
            assertTrue(tree.shows(context.getString(R.string.history_older_title_unknown)), tree.texts().toString())
        }
        show(null) { tree ->
            assertFalse(tree.texts().any { it == title || it == context.getString(R.string.history_older_allow) })
        }
    }

    private fun show(hint: OlderDataHint?, block: (ComposeTree) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent {
            PhaseWakeTheme(ThemeMode.LIGHT) {
                HistoryScreen(
                    HistoryState(emptyList(), loadStages = null, healthConnected = true, olderHint = hint),
                    PaddingValues(),
                )
            }
        }
        block(ComposeTree(controller.get()).also { it.idle(500) })
        controller.pause().stop().destroy()
    }
}
