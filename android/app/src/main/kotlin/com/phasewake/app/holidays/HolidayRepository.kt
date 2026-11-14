package com.phasewake.app.holidays

import android.content.Context
import android.util.JsonReader
import android.util.Log
import com.phasewake.app.SettingsRepository
import com.phasewake.app.core.DiagKind
import com.phasewake.app.core.HolidayList
import com.phasewake.app.core.Holidays
import com.phasewake.app.shared.HolidayStore
import com.phasewake.app.shared.WearSync
import java.io.IOException
import java.io.InputStreamReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ein Land der Datendatei mit seinen Regionen (Code → Name, wie in der Datei). */
data class HolidayCountry(val code: String, val regions: Map<String, String>)

/**
 * Feiertage auf dem Handy: Datendatei `assets/holidays.json.gz` (Python-Paket «holidays», MIT), Wahl von Land
 * und Region, Auflösen der nächsten [Holidays.SYNC_DAYS] Tage, Ablegen in [HolidayStore] und Senden an die Uhr.
 * Ohne Netz und ohne Standort: Das Land kommt aus der Region des Geräts, bis der Nutzer selbst wählt.
 */
class HolidayRepository(
    private val context: Context,
    private val settings: SettingsRepository,
    val store: HolidayStore,
    private val sync: WearSync,
    private val log: (DiagKind, String) -> Unit,
) {
    private val mutex = Mutex()
    private var countries: List<HolidayCountry>? = null

    /** Zuletzt gelesene geschätzte Tage ([estimated]) mit «Region@Tag». */
    @Volatile
    private var estimatedCache: Pair<String, Set<LocalDate>>? = null

    /** Länder mit 2-Buchstaben-Code (die 3-Buchstaben-Duplikate der Datei nicht), nach Code sortiert. Liest die Datei einmal. */
    suspend fun countries(): List<HolidayCountry> = mutex.withLock {
        countries ?: withContext(Dispatchers.IO) { readCountries() }.also { countries = it }
    }

    /** Gewähltes Land und Region; noch nie gewählt: das Land des Geräts (falls in der Datei), ganzes Land. */
    suspend fun choice(): Pair<String, String> {
        settings.holidayChoice.first()?.let { return it }
        val device = Locale.getDefault().country.uppercase(Locale.ROOT)
        return if (countries().any { it.code == device }) device to "" else "" to ""
    }

    /** Speichert die Wahl und rechnet die Feiertage neu. */
    suspend fun choose(country: String, region: String) {
        settings.setHolidayChoice(country, region)
        refresh(force = true)
    }

    /**
     * Rechnet die Feiertage neu, wenn die Wahl sich geändert hat, der Stand älter als [REFRESH_AFTER] ist, nach älteren
     * Regeln gerechnet wurde ([RULES]) oder [force]. Nur sichere Tage gelten als frei, geschätzte nicht ([Holidays.isEstimated]).
     * @return ob neu gerechnet wurde (dann ist auch die Uhr benachrichtigt).
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        val (country, region) = choice()
        val code = Holidays.regionCode(country, region)
        val stale = Instant.now().toEpochMilli() - store.updatedAt() > REFRESH_AFTER.toMillis()
        val outdated = settings.holidayRules() < RULES
        if (!force && !stale && !outdated && store.list.first().region == code) return false
        val days = if (country.isEmpty()) HolidayDays.NONE else withContext(Dispatchers.IO) { readDays(country, region) }
        estimatedCache = (code + "@" + LocalDate.now()) to days.estimated
        val list = HolidayList(code, days.sure)
        val now = Instant.now().toEpochMilli()
        store.set(list, now)
        sync.publishHolidays(list, now)
        settings.setHolidayRules(RULES)
        log(DiagKind.SYNC, "holidays ${code.ifEmpty { "off" }} ${days.sure.size} estimated ${days.estimated.size}")
        return true
    }

    /**
     * Geschätzte Feiertage der nächsten [Holidays.SYNC_DAYS] Tage der gewählten Region (Audit F-2): gelten nicht als frei,
     * die Einstellungen zeigen den nächsten mit «geschätzt». Liest die Datei höchstens einmal je Region und Tag.
     */
    suspend fun estimated(): Set<LocalDate> {
        val (country, region) = choice()
        val key = Holidays.regionCode(country, region) + "@" + LocalDate.now()
        estimatedCache?.takeIf { it.first == key }?.let { return it.second }
        val days = if (country.isEmpty()) emptySet() else withContext(Dispatchers.IO) { readDays(country, region).estimated }
        estimatedCache = key to days
        return days
    }

    /** Erneut an die Uhr (z. B. eine neu gekoppelte), ohne neu zu rechnen. */
    suspend fun republish() {
        sync.publishHolidays(store.list.first(), store.updatedAt().takeIf { it > 0 } ?: Instant.now().toEpochMilli())
    }

    private fun open(): JsonReader =
        JsonReader(InputStreamReader(GZIPInputStream(context.assets.open(FILE)), Charsets.UTF_8))

    private fun readCountries(): List<HolidayCountry> = try {
        open().use { reader ->
            val out = mutableListOf<HolidayCountry>()
            reader.inObject("countries") {
                reader.beginObject()
                while (reader.hasNext()) {
                    val code = reader.nextName()
                    var regions = emptyMap<String, String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "regions") regions = reader.readStringMap() else reader.skipValue()
                    }
                    reader.endObject()
                    if (code.length == 2) out += HolidayCountry(code, regions)
                }
                reader.endObject()
            }
            out.sortedBy { it.code }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Feiertage nicht lesbar", e)
        emptyList()
    }

    private fun readDays(country: String, region: String): HolidayDays = try {
        open().use { reader ->
            var national = emptyList<Pair<String, String>>()
            var regional = emptyList<Pair<String, String>>()
            reader.inObject("countries") {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != country) {
                        reader.skipValue()
                    } else reader.inObject("days") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "" -> national = reader.readDates()
                                region.takeIf { it.isNotEmpty() } -> regional = reader.readDates()
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    }
                }
                reader.endObject()
            }
            HolidayDays.of(national, regional, LocalDate.now())
        }
    } catch (e: IOException) {
        Log.w(TAG, "Feiertage nicht lesbar", e)
        HolidayDays.NONE
    }

    internal companion object {
        private const val TAG = "HolidayRepository"
        private const val FILE = "holidays.json.gz"

        /**
         * Stand der Regeln für die gespeicherten Feiertage ([SettingsRepository.holidayRules]); steigt, wenn sich ändert,
         * welche Tage als frei gelten. 1: geschätzte Tage nicht mehr (Audit F-2, 14.11.2026).
         */
        const val RULES = 1

        /** So lange gilt ein Stand; danach wird das Fenster der nächsten 400 Tage nachgeführt. */
        private val REFRESH_AFTER: Duration = Duration.ofDays(7)
    }
}

/**
 * Feiertage der nächsten [Holidays.SYNC_DAYS] Tage: [sure] gelten als frei, [estimated] nur geschätzt
 * ([Holidays.isEstimated], Audit F-2; ein Tag, der auch sicher ein Feiertag ist, steht nur in [sure]).
 */
internal data class HolidayDays(val sure: Set<LocalDate>, val estimated: Set<LocalDate>) {
    companion object {
        val NONE = HolidayDays(emptySet(), emptySet())

        /** Aus den Einträgen (Datum, Name) des Landes ([national]) und der Region ([regional]) ab [today]. */
        fun of(national: List<Pair<String, String>>, regional: List<Pair<String, String>>, today: LocalDate): HolidayDays {
            val (estNational, sureNational) = national.partition { Holidays.isEstimated(it.second) }
            val (estRegional, sureRegional) = regional.partition { Holidays.isEstimated(it.second) }
            val sure = Holidays.resolve(sureNational.map { it.first }, sureRegional.map { it.first }, today)
            val estimated = Holidays.resolve(estNational.map { it.first }, estRegional.map { it.first }, today) - sure
            return HolidayDays(sure, estimated)
        }
    }
}

/** Im aktuellen Objekt zum Schlüssel [name] gehen und dort [block] ausführen; andere Schlüssel überspringen. */
private inline fun JsonReader.inObject(name: String, block: () -> Unit) {
    beginObject()
    while (hasNext()) {
        if (nextName() == name) block() else skipValue()
    }
    endObject()
}

/** Liest ein Objekt mit Textwerten. */
private fun JsonReader.readStringMap(): Map<String, String> {
    val out = linkedMapOf<String, String>()
    beginObject()
    while (hasNext()) out[nextName()] = nextString()
    endObject()
    return out
}

/** Liest eine Liste von Paaren [Datum, Name] (Name fehlt: leer). */
private fun JsonReader.readDates(): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    beginArray()
    while (hasNext()) {
        beginArray()
        val date = nextString()
        val name = if (hasNext()) nextString() else ""
        while (hasNext()) skipValue()
        endArray()
        out += date to name
    }
    endArray()
    return out
}
