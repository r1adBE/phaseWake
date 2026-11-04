package com.phasewake.app.ui.settings

import android.content.Context
import com.phasewake.app.QuietApp
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lizenzen (Audit M-05): Jede Datei in `licenses/` steht in der Liste und umgekehrt, die Texte liegen als Assets in
 * der App, und lange Texte werden in Absätze geteilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietApp::class)
class LicensesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** Ordner `licenses/` des Repositorys, vom Arbeitsverzeichnis der Tests (android/app) aus gesucht. */
    private val folder: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, Licenses.FOLDER) }
        .first { it.isDirectory }

    @Test
    fun everyFileIsListedAndEveryEntryHasAFile() {
        val files = folder.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val listed = Licenses.entries.map { it.file }
        assertEquals(listed.size, listed.toSet().size, "Datei doppelt in der Liste")
        assertEquals(files, listed.toSet())
    }

    @Test
    fun textsAreBundledAsAssets() {
        Licenses.entries.forEach { entry ->
            val text = context.assets.open(entry.file).bufferedReader().use { it.readText() }
            assertEquals(File(folder, entry.file).readText(), text, entry.file)
        }
    }

    @Test
    fun paragraphsSplitOnBlankLines() {
        assertEquals(listOf("A\n  b", "C"), Licenses.paragraphs("A\n  b\n\n \nC\r\n"))
        assertTrue(Licenses.paragraphs("").isEmpty())
        assertTrue(Licenses.paragraphs(File(folder, "Apache-2.0.txt").readText()).size > 10)
    }
}
