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
        // Dasselbe mit den Werten des Vektors verschlüsselt ergibt (bis auf den Inhalt) dieselbe Datei.
        assertContentEquals(file, BackupFile.encrypt(plain, password, salt, nonce))
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
        assertContentEquals(file, BackupFile.encrypt(expected.encodeToByteArray(), "Mondlicht-\u00e4€-2026", salt, nonce))
    }

    @Test
    fun shortPasswordIsRefused() {
        assertFalse(BackupFile.passwordOk(""))
        assertFalse(BackupFile.passwordOk("1234567"))
        assertTrue(BackupFile.passwordOk("12345678"))
        // «ä» als NFD (zwei Codepunkte) zählt als ein Zeichen: «abcdefä» hat 7.
        assertFalse(BackupFile.passwordOk("abcdefa\u0308"))
        assertTrue(BackupFile.passwordOk("abcdefga\u0308"))
        assertFailsWith<IllegalArgumentException> { BackupFile.encrypt(ByteArray(1), "", salt, nonce) }
        assertFailsWith<IllegalArgumentException> { BackupFile.encrypt(ByteArray(1), "1234567", salt, nonce) }
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
        val sealed = BackupFile.encrypt(payload.toJson().encodeToByteArray(), "geheim123", salt, nonce)
        assertContentEquals(BackupFile.MAGIC, sealed.copyOfRange(0, 5))
        assertEquals(payload, BackupPayload.fromJson(BackupFile.decrypt(sealed, "geheim123").decodeToString()))
    }

    @Test
    fun backupKeepsClearedAt() {
        val cleared = payload.copy(settings = payload.settings.copy(historyClearedAt = 1_790_000_000_000L))
        assertEquals(1_790_000_000_000L, BackupPayload.fromJson(cleared.toJson()).settings.historyClearedAt)
        assertTrue("\"historyClearedAt\":null" in payload.toJson())
        assertNull(BackupPayload.fromJson(payload.toJson()).settings.historyClearedAt)
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
        val sealed = BackupFile.encrypt("{}".encodeToByteArray(), "richtig1", salt, nonce)
        assertEquals(BackupException.Reason.WRONG_PASSWORD, assertFailsWith<BackupException> { BackupFile.decrypt(sealed, "falsch12") }.reason)
        val changed = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(BackupException.Reason.WRONG_PASSWORD, assertFailsWith<BackupException> { BackupFile.decrypt(changed, "richtig1") }.reason)
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
}
