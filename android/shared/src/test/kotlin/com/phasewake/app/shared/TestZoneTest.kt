package com.phasewake.app.shared

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Die Tests laufen in einer Zone mit Sommer- und Winterzeit, nicht in UTC der CI (Audit CODE-10,
 * `gradle/robolectric.gradle.kts`): Fehlt die Einstellung, scheitert dieser Test statt still in UTC zu prüfen.
 */
class TestZoneTest {
    @Test
    fun testsRunInZurich() {
        assertEquals(ZoneId.of("Europe/Zurich"), ZoneId.systemDefault())
    }
}
