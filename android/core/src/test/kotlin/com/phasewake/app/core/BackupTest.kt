package com.phasewake.app.core

import java.io.File
import java.time.DayOfWeek
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sicherungsdatei und ihr JSON; der Testvektor kommt von `make-backup-vector.py`. Gegenstück: `BackupTests` (Swift). */
class BackupTest {
    private val testdata = File(System.getProperty("phasewake.testdata") ?: "../../shared/testdata")
    private val salt = ByteArray(BackupFile.SALT_BYTES) { it.toByte() }
    private val nonce = ByteArray(BackupFile.NONCE_BYTES) { (it + 100).toByte() }

    private val payload = BackupPayload(
        createdAt = 1_793_260_800_000L,
        alarms = Codec.encodeAlarms(listOf(AlarmSpec(id = "a", hour = 6, minute = 30, days = setOf(DayOfWeek.MONDAY), label = "Früh \"zeitig\"\n"))),
        nights = listOf("phasewake-night 1\na|1|2|3|DEADLINE|0|\n\n"),
        notes = "2026-10-28|caffeine",
        customNotes = "custom:1|Tee\twarm\u0001",
        routineItems = null,
        routineTicks = "",
        settings = BackupSettings(sleepGoal = 420, bedtimeReminder = true, theme = "LIGHT", holidayCountry = "DE", holidayRegion = "BY"),
    )

    @Test
    fun vectorFromPythonDecrypts() {
        val file = Base64.getDecoder().decode(File(testdata, "backup-v1.pwbak.b64").readText().trim())
        val password = File(testdata, "backup-v1.password").readText().trim()
        val plain = BackupFile.decrypt(file, password)
        assertEquals(File(testdata, "backup-v1.json").readText().trim(), plain.decodeToString())
        val p = BackupPayload.fromJson(plain.decodeToString())
        assertEquals(1_793_260_800_000L, p.createdAt)
        val alarms = Codec.decodeAlarms(p.alarms)
        assertEquals(2, alarms.size)
        assertEquals("Arbeit | früh", alarms[0].label)
        assertEquals(1_793_338_200_000L, alarms[0].skipUntil)
        assertTrue(alarms[0].skipHolidays)
        assertEquals(WakeReason.LIGHT_SLEEP, Codec.decodeNight(p.nights.single()).reason)
        assertEquals("Spät gegessen «Pizza»", SleepNotes.decodeCustom(p.customNotes).single().name)
        assertEquals("Tee \"Kamille\"", Routine.decodeItems(p.routineItems)[1].text)
        assertEquals(BackupSettings(480, true, false, true, "DARK", "CH", "BE"), p.settings)
        // Geschrieben wird Formatversion 2: mit den Werten des Vektors genau die Datei backup-v2.
        val v2 = Base64.getDecoder().decode(File(testdata, "backup-v2.pwbak.b64").readText().trim())
        assertContentEquals(v2, BackupFile.encrypt(plain, password, salt, nonce))
        assertContentEquals(plain, BackupFile.decrypt(v2, password))
    }

    @Test
    fun headerSelectsRounds() {
        // «PWBK1» liest mit 210 000, «PWBK2» mit 600 000 Runden: derselbe Inhalt unter dem anderen Kopf passt nicht.
        val password = File(testdata, "backup-v1.password").readText().trim()
        val v1 = Base64.getDecoder().decode(File(testdata, "backup-v1.pwbak.b64").readText().trim())
        assertContentEquals("PWBK1".encodeToByteArray(), v1.copyOfRange(0, 5))
        val relabelled = BackupFile.MAGIC + v1.copyOfRange(BackupFile.MAGIC_V1.size, v1.size)
        assertEquals(BackupException.Reason.WRONG_PASSWORD, assertFailsWith<BackupException> { BackupFile.decrypt(relabelled, password) }.reason)
        assertEquals(600_000, BackupFile.ITERATIONS)
        assertEquals(210_000, BackupFile.ITERATIONS_V1)
    }

    @Test
    fun unicodePasswordIsNormalised() {
        // Passwort-Datei absichtlich in NFD («a» + Trema), Schlüssel aus NFC abgeleitet.
        val file = Base64.getDecoder().decode(File(testdata, "backup-v1-nfc.pwbak.b64").readText().trim())
        val nfd = File(testdata, "backup-v1-nfc.password").readText().trim()
        assertEquals("Mondlicht-a\u0308€-2026", nfd)
        val expected = File(testdata, "backup-v1.json").readText().trim()
        assertEquals(expected, BackupFile.decrypt(file, nfd).decodeToString())
        assertEquals(expected, BackupFile.decrypt(file, "Mondlicht-\u00e4€-2026").decodeToString())
        val v2 = Base64.getDecoder().decode(File(testdata, "backup-v2-nfc.pwbak.b64").readText().trim())
        assertContentEquals(v2, BackupFile.encrypt(expected.encodeToByteArray(), "Mondlicht-\u00e4€-2026", salt, nonce))
        assertEquals(expected, BackupFile.decrypt(v2, nfd).decodeToString())
    }

    @Test
    fun shortOrTrivialPasswordIsRefused() {
        // Regel im Einzelnen: [BackupPasswordTest]; hier nur, dass Sichern sie anwendet.
        assertFalse(BackupFile.passwordOk(""))
        assertFalse(BackupFile.passwordOk("Mondlicht"))
        assertFalse(BackupFile.passwordOk("1234567890"))
        assertTrue(BackupFile.passwordOk("Mondlicht-2026"))
        assertFailsWith<IllegalArgumentException> { BackupFile.encrypt(ByteArray(1), "", salt, nonce) }
        assertFailsWith<IllegalArgumentException> { BackupFile.encrypt(ByteArray(1), "12345678", salt, nonce) }
        assertFailsWith<IllegalArgumentException> { BackupFile.encrypt(ByteArray(1), "qwertyuiop", salt, nonce) }
        assertTrue(BackupFile.MAX_FILE_BYTES >= 1_000_000L)
    }

    @Test
    fun readLimitedRefusesLargeFile() {
        val data = ByteArray(200_000) { it.toByte() }
        assertContentEquals(data, BackupFile.readLimited(data.inputStream(), limit = 200_000L))
        assertNull(BackupFile.readLimited(data.inputStream(), limit = 199_999L))
        assertContentEquals(ByteArray(0), BackupFile.readLimited(ByteArray(0).inputStream()))
        assertContentEquals(data, BackupFile.readLimited(data.inputStream()))
    }

    @Test
    fun roundTrip() {
        val sealed = BackupFile.encrypt(payload.toJson().encodeToByteArray(), "geheim-123", salt, nonce)
        assertContentEquals(BackupFile.MAGIC, sealed.copyOfRange(0, 5))
        assertEquals(payload, BackupPayload.fromJson(BackupFile.decrypt(sealed, "geheim-123").decodeToString()))
    }

    @Test
    fun backupKeepsClearedAt() {
        val cleared = payload.copy(settings = payload.settings.copy(historyClearedAt = 1_790_000_000_000L))
        assertEquals(1_790_000_000_000L, BackupPayload.fromJson(cleared.toJson()).settings.historyClearedAt)
        assertTrue("\"historyClearedAt\":null" in payload.toJson())
        assertNull(BackupPayload.fromJson(payload.toJson()).settings.historyClearedAt)
    }

    @Test
    fun backupKeepsNextAlarmNotice() {
        // Fehlt der Schlüssel (ältere Sicherung, Testvektor), bleibt die Mitteilung an.
        assertTrue(BackupPayload.fromJson(File(testdata, "backup-v1.json").readText().trim()).settings.nextAlarmNotice)
        assertTrue("\"nextAlarmNotice\":true" in payload.toJson())
        val off = payload.copy(settings = payload.settings.copy(nextAlarmNotice = false))
        assertFalse(BackupPayload.fromJson(off.toJson()).settings.nextAlarmNotice)
    }

    @Test
    fun backupKeepsAutoOnset() {
        // Fehlt der Schlüssel (ältere Sicherung, Testvektor), bleibt die Einschlaf-Messung an.
        assertTrue(BackupPayload.fromJson(File(testdata, "backup-v1.json").readText().trim()).settings.autoOnset)
        assertTrue("\"autoOnset\":true" in payload.toJson())
        val off = payload.copy(settings = payload.settings.copy(autoOnset = false))
        assertFalse(BackupPayload.fromJson(off.toJson()).settings.autoOnset)
    }

    @Test
    fun backupKeepsHealthFeelings() {
        val rated = payload.copy(healthFeelings = "2026-10-01|4\n2026-10-02|2")
        assertTrue("\"healthFeelings\":\"2026-10-01|4\\n2026-10-02|2\"" in rated.toJson())
        assertEquals(rated, BackupPayload.fromJson(rated.toJson()))
        // Ältere Sicherungen (auch der Testvektor) ohne den Schlüssel: leer.
        val plain = File(testdata, "backup-v1.json").readText().trim()
        assertEquals("", BackupPayload.fromJson(plain).healthFeelings)
        assertEquals("", payload.healthFeelings)
    }

    @Test
    fun wrongPasswordOrChangedFile() {
        val sealed = BackupFile.encrypt("{}".encodeToByteArray(), "richtig-12", salt, nonce)
        assertEquals(BackupException.Reason.WRONG_PASSWORD, assertFailsWith<BackupException> { BackupFile.decrypt(sealed, "falsch12") }.reason)
        val changed = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(BackupException.Reason.WRONG_PASSWORD, assertFailsWith<BackupException> { BackupFile.decrypt(changed, "richtig-12") }.reason)
    }

    @Test
    fun shortPasswordStillOpensOldBackup() {
        // Vor der Regel (10.11.2026) reichten 8 Zeichen: Eine solche Sicherung («{}», Passwort «1234abcd», Salz und
        // Nonce wie oben, mit Python erzeugt) öffnet das Wiederherstellen weiter. Swift: gleicher Test.
        val old = Base64.getDecoder().decode("UFdCSzIAAQIDBAUGBwgJCgsMDQ4PZGVmZ2hpamtsbW5vt4PzqnJ6aYzD2IdWUvOlrPj0")
        assertFalse(BackupFile.passwordOk("1234abcd"))
        assertEquals("{}", BackupFile.decrypt(old, "1234abcd").decodeToString())
    }

    @Test
    fun foreignFileIsRejected() {
        assertEquals(BackupException.Reason.NOT_A_BACKUP, assertFailsWith<BackupException> { BackupFile.decrypt("hallo".encodeToByteArray(), "x") }.reason)
        val wrongMagic = ByteArray(60) { 1 }
        assertEquals(BackupException.Reason.NOT_A_BACKUP, assertFailsWith<BackupException> { BackupFile.decrypt(wrongMagic, "x") }.reason)
        assertFailsWith<IllegalArgumentException> { BackupFile.encrypt(ByteArray(0), "x", ByteArray(3), nonce) }
    }

    @Test
    fun jsonIsTolerant() {
        val minimal = BackupPayload.fromJson("""{"format":"phasewake-backup","version":2,"alarms":"phasewake-alarms 1\n","neu":[1,2.5,{"x":null}]}""")
        assertEquals(0L, minimal.createdAt)
        assertEquals(emptyList(), minimal.nights)
        assertNull(minimal.routineItems)
        assertEquals(BackupSettings(), minimal.settings)
        val escaped = BackupPayload.fromJson(""" { "format" : "phasewake-backup", "alarms" : "aä\/\b\f\r\t" , "nights" : [ ] , "settings" : { } } """)
        assertEquals("aä/\b\u000C\r\t", escaped.alarms)
    }

    @Test
    fun jsonRejectsBrokenOrForeign() {
        val bad = listOf(
            "", "[]", """{"format":"anders","alarms":""}""", """{"format":"phasewake-backup"}""",
            """{"format":"phasewake-backup","alarms":"x"""", """{"format":"phasewake-backup","alarms":"x"} x""",
            """{"format":"phasewake-backup","alarms":"\q"}""", """{"format":"phasewake-backup","alarms":"\u12"}""",
            """{"format":"phasewake-backup","alarms":"\uZZZZ"}""", """{"format":"phasewake-backup","alarms":"x","n":tru}""",
            """{"format":"phasewake-backup","alarms":"x","n":-}""", """{"format":"phasewake-backup" "alarms":"x"}""",
            """{format:1}""", """{"format":"phasewake-backup","alarms":"x","n":[1 2]}""", """{"a":"\""",
        )
        for (text in bad) assertFailsWith<CodecException> { BackupPayload.fromJson(text) }
    }

    @Test
    fun jsonDepthIsLimited() {
        // Das Objekt selbst ist Stufe 1; mit 63 Listen darin genau [BackupJson.MAX_DEPTH] = 64 Stufen.
        fun nested(lists: Int) = """{"format":"phasewake-backup","alarms":"x","n":${"[".repeat(lists)}${"]".repeat(lists)}}"""
        assertEquals(64, BackupJson.MAX_DEPTH)
        assertEquals("x", BackupPayload.fromJson(nested(63)).alarms)
        assertFailsWith<CodecException> { BackupPayload.fromJson(nested(64)) }
        // Klammern in Texten zählen nicht.
        assertEquals("[[[", BackupPayload.fromJson("""{"format":"phasewake-backup","alarms":"[[[","n":[[]]}""").alarms)
        // Sehr tief: Fehler statt Stapelüberlauf.
        assertFailsWith<CodecException> { BackupPayload.fromJson("[".repeat(100_000)) }
    }
}
