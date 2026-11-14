package com.phasewake.app.shared

import android.content.pm.PackageInfo
import com.google.android.gms.wearable.DataMap
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Build-Nummer der Testversion in «Über», Diagnose und Status-Paket der Uhr (Entscheid r1AD 14.11.2026). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuildNumberTest {
    @Test
    fun labelWithAndWithoutNumber() {
        assertEquals("16.2.2 (96)", BuildNumber.label("16.2.2", 96))
        assertEquals("16.2.2", BuildNumber.label("16.2.2", null))
    }

    @Test
    fun watchCodeLosesItsOffset() {
        assertEquals(96, BuildNumber.of(96, watch = false))
        assertEquals(96, BuildNumber.of(1_000_096, watch = true))
        // Lokal gebaut (Code 1 bzw. 1 000 001): unbekannt, also ohne Klammer.
        assertNull(BuildNumber.of(1, watch = false))
        assertNull(BuildNumber.of(1_000_001, watch = true))
        // Ungültig: Uhr-Code ohne Abstand, Handy-Code mit Abstand.
        assertNull(BuildNumber.of(96, watch = true))
        assertNull(BuildNumber.of(1_000_096, watch = false))
    }

    @Test
    fun differsOnlyWithBothNumbers() {
        assertTrue(BuildNumber.differs(96, 95))
        assertFalse(BuildNumber.differs(96, 96))
        assertFalse(BuildNumber.differs(96, null))
        assertFalse(BuildNumber.differs(null, 95))
    }

    @Test
    fun ownLabelReadsThePackage() {
        val app = RuntimeEnvironment.getApplication()
        val info = PackageInfo().apply {
            packageName = app.packageName
            versionName = "16.2.2"
            longVersionCode = 1_000_096
        }
        shadowOf(app.packageManager).installPackage(info)
        assertEquals("16.2.2 (96)", BuildNumber.ownLabel(app, watch = true))
        assertEquals(96, BuildNumber.own(app, watch = true))
        assertEquals("16.2.2", BuildNumber.ownLabel(app, watch = false), "Uhr-Code als Handy gelesen: ungültig")
    }

    @Test
    fun statusPacketCarriesTheBuild() {
        val with = DataMap.fromByteArray(WearSync.statusRequest(true, 50, 10_000L, build = 96).data!!)
        assertEquals(96, WearSync.readBuild(with))
        // Neue Uhr ohne bekannte Nummer: Feld fehlt.
        val without = DataMap.fromByteArray(WearSync.statusRequest(true, 50, 10_000L).data!!)
        assertFalse(without.containsKey(DataLayer.KEY_BUILD))
        assertNull(WearSync.readBuild(without))
        // Ältere Uhr (Status ohne Feld) oder lokaler Build: unbekannt, also kein Hinweis.
        assertNull(WearSync.readBuild(DataMap().apply { putBoolean(DataLayer.KEY_READY, true) }))
        assertNull(WearSync.readBuild(DataMap().apply { putInt(DataLayer.KEY_BUILD, 1) }))
    }
}
