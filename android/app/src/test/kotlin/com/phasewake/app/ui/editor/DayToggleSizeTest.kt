package com.phasewake.app.ui.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.phasewake.app.OfflineApp
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.ring.ComposeTree
import com.phasewake.app.ui.theme.PhaseWakeTheme
import com.phasewake.app.ui.theme.ThemeMode
import java.time.DayOfWeek
import java.time.format.TextStyle
import kotlin.math.abs
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tag-Schalter im Editor: Auch auf einem kleinen Handy (360 dp breit) ist jede Tippfläche
 * mindestens 48 dp hoch, und die sieben Flächen liegen lückenlos nebeneinander (die Breite reicht für 7 × 48 dp nicht).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = OfflineApp::class, qualifiers = "w360dp-h640dp-port")
class DayToggleSizeTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun dayTogglesAreAtLeast48dpHighAndWithoutGaps() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        // Wie im Editor: 16 dp Rand der Seite um die Karte.
        controller.get().setContent {
            PhaseWakeTheme(ThemeMode.LIGHT) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { DaysSection(EditorState(AlarmSpec(id = "d", hour = 7, minute = 0))) }
            }
        }
        val tree = ComposeTree(controller.get()).also { it.idle(500) }
        val locale = context.resources.configuration.locales[0]
        val names = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, locale) }.toSet()
        val days = tree.nodes()
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox }
            .filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any(names::contains) == true }
            .sortedBy { it.boundsInRoot.left }
        assertEquals(7, days.size, "sieben Tag-Schalter")
        val density = context.resources.displayMetrics.density
        days.forEach { day ->
            val heightDp = day.size.height / density
            assertTrue(heightDp >= 48f - 0.5f, "Tippfläche nur $heightDp dp hoch")
        }
        // Lückenlos: Jede Fläche beginnt, wo die vorige endet (ein Tipp zwischen zwei Kreisen trifft einen Tag).
        days.zipWithNext().forEach { (a, b) ->
            assertTrue(abs(a.boundsInRoot.right - b.boundsInRoot.left) <= 1f, "Lücke zwischen ${a.boundsInRoot} und ${b.boundsInRoot}")
        }
        controller.pause().stop().destroy()
        ComposeTree.drain()
    }
}
