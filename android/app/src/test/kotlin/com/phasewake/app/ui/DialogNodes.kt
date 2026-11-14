package com.phasewake.app.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import org.robolectric.shadows.ShadowDialog
import kotlin.test.fail

/**
 * Rückfrage (Compose-`AlertDialog`) unter Robolectric: Sie liegt in einem eigenen Fenster, das [com.phasewake.app.ring.ComposeTree]
 * (nur die Activity) nicht sieht. Liest den Semantik-Baum des zuletzt gezeigten Dialogs.
 */
internal object DialogNodes {
    private fun root(): ViewRootForTest? {
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } ?: return null
        return find(dialog.window?.decorView ?: return null)
    }

    private fun find(view: View): ViewRootForTest? {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun nodes(): List<SemanticsNode> {
        val root = root() ?: return emptyList()
        root.measureAndLayoutForTest()
        return root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
    }

    private fun text(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()

    /** Texte der offenen Rückfrage; leer, wenn keine offen ist. */
    fun texts(): List<String> = nodes().map(::text).filter(String::isNotEmpty)

    /** Tippt in der offenen Rückfrage auf [label]. */
    fun click(label: String) {
        val node = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && text(it) == label }
            ?: fail("Rückfrage ohne «$label»; Texte: ${texts()}")
        node.config[SemanticsActions.OnClick].action?.invoke() ?: fail("«$label» ohne Aktion")
    }
}
