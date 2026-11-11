package com.phasewake.app.ring

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import kotlin.test.fail

/**
 * Bildschirm einer Compose-Activity unter Robolectric, ohne Compose-Testbibliothek: liest den Semantik-Baum (wie ein
 * Bildschirmleser) und löst Klicks über dessen Aktionen aus, Halten über echte Berührungen an der Ansicht.
 */
internal class ComposeTree(private val activity: Activity) {

    private val root: ViewRootForTest
        get() = find(activity.window.decorView) ?: fail("keine Compose-Ansicht in ${activity::class.simpleName}")

    private fun find(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }

    /**
     * Alle Knoten, zusammengefasst wie für Bildschirmleser (eine Taste = ein Knoten mit ihrem Text). Vorher messen und
     * anordnen: Robolectric zeichnet nicht, und Compose ordnet geänderte Teile sonst erst beim Zeichnen an.
     */
    fun nodes(): List<SemanticsNode> {
        val root = root
        root.measureAndLayoutForTest()
        return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
    }

    /** Sichtbarer Text eines Knotens, mehrere Teile mit Leerzeichen verbunden. */
    fun text(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    /** Alle Texte auf dem Bildschirm. */
    fun texts(): List<String> = nodes().map(::text).filter(String::isNotEmpty)

    /** Ob ein Text (genau) auf dem Bildschirm steht. */
    fun shows(text: String): Boolean = nodes().any { text(it) == text }

    /** Antippbarer Knoten mit Text, Beschreibung oder Aktionsname [label]. */
    fun clickable(label: String): SemanticsNode = nodes().firstOrNull { node ->
        val click = node.config.getOrNull(SemanticsActions.OnClick) ?: return@firstOrNull false
        click.label == label || text(node) == label ||
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    } ?: fail("nichts zum Antippen mit «$label»; Texte: ${texts()}")

    /** Ob [label] antippbar und eingeschaltet ist. */
    fun enabled(label: String): Boolean = !clickable(label).config.contains(SemanticsProperties.Disabled)

    /** Tippt [label] an (wie ein Bildschirmleser per Doppeltipp) und lässt den Bildschirm nachziehen. */
    fun click(label: String) {
        val action = clickable(label).config[SemanticsActions.OnClick].action ?: fail("«$label» ohne Aktion")
        action()
        idle()
    }

    /** Hält den Finger [millis] Millisekunden auf [label] und lässt dann los (echte Berührung, nicht Semantik). */
    fun hold(label: String, millis: Long) {
        val bounds = clickable(label).boundsInRoot
        val x = bounds.center.x
        val y = bounds.center.y
        val view = root.view
        val down = SystemClock.uptimeMillis()
        touch(view, down, MotionEvent.ACTION_DOWN, x, y)
        idle(millis)
        // Das Warten im Halten läuft auf echter Zeit (AndroidUiDispatcher hat kein eigenes delay): echte Zeit geben,
        // damit die Prüfung nach der Testzeit noch einmal drankommt, bevor der Finger losgelassen wird.
        repeat(SETTLE_ROUNDS) {
            Thread.sleep(SETTLE_SLEEP_MILLIS)
            idle(FRAME_STEP_MILLIS)
        }
        touch(view, down, MotionEvent.ACTION_UP, x, y)
        idle()
    }

    private fun touch(view: View, downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    /**
     * Lässt die Hauptschleife [millis] Millisekunden laufen (Bilder, Animationen, Wartezeiten), siehe [advance].
     */
    fun idle(millis: Long = FRAME_MILLIS) = advance(millis)

    companion object {
        /**
         * Vor jedem Test: Bilder nur, wenn die Testzeit vorrückt. Sonst hielten endlose Animationen (Nachthimmel,
         * Punkt) jedes idle() auf, weil Robolectric für jedes Bild die Zeit selbst weiterstellt.
         */
        fun prepareFrames() {
            ShadowChoreographer.setPaused(true)
        }

        /**
         * Nach jedem Test, wenn der Bildschirm zerstört ist: offene Arbeit von Compose (Bilder, Dispatcher) erledigen.
         * Robolectric leert danach die Hauptschleife; bliebe dabei eine Bestellung von Compose liegen, käme sie im
         * nächsten Test nie an und der Bildschirm zeichnete nicht mehr neu.
         */
        fun drain() = advance(DRAIN_MILLIS)

        /**
         * Lässt die Hauptschleife [millis] Millisekunden laufen, in Schritten von etwa einem Bild: Mit angehaltenem
         * Choreographer kommt je Vorrücken der Testzeit höchstens ein Bild. Änderungen ausserhalb der Komposition
         * (Antippen aus dem Test) meldet es zuerst, wie die Compose-Testbibliothek.
         */
        private fun advance(millis: Long) {
            val looper = shadowOf(Looper.getMainLooper())
            var left = millis
            while (left > 0) {
                Snapshot.sendApplyNotifications()
                val step = minOf(left, FRAME_STEP_MILLIS)
                looper.idleFor(Duration.ofMillis(step))
                left -= step
            }
            looper.idle()
        }

        /** Nach dem Zerstören: genug Bilder, damit alles Offene abläuft. */
        private const val DRAIN_MILLIS = 500L

        /** Einige Bilder: genug für Neuzeichnen und Effekte nach einer Änderung. */
        private const val FRAME_MILLIS = 100L

        /** Ein Bild bei 60 Hz (etwas mehr als die Bildrate des Choreographers unter Robolectric). */
        private const val FRAME_STEP_MILLIS = 16L

        /** Echte Wartezeit vor dem Loslassen: Runden à [SETTLE_SLEEP_MILLIS]. */
        private const val SETTLE_ROUNDS = 20
        private const val SETTLE_SLEEP_MILLIS = 30L
    }
}
