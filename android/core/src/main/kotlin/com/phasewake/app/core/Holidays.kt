package com.phasewake.app.core

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Feiertage eines Landes (und einer Region) als Daten, wie sie das Handy an die Uhren schickt.
 *
 * @property region «CH» (ganzes Land) oder «CH-BE» (Land und Region); leer = keine Feiertage gewählt.
 * @property dates Feiertage als Kalendertage, unabhängig von der Zeitzone.
 */
data class HolidayList(val region: String, val dates: Set<LocalDate>)

/**
 * Feiertage für «An Feiertagen nicht wecken». Die Daten (Python-Paket «holidays», MIT) liegen nur auf dem
 * Handy; die Uhren bekommen die Tage der nächsten [SYNC_DAYS] Tage als kurze Liste ([encode]).
 * Lokale Feiertage einzelner Gemeinden sind nicht enthalten.
 */
object Holidays {

    /** So weit voraus (Tage ab heute) gehen die Feiertage an die Uhren; gut ein Jahr. */
    const val SYNC_DAYS: Int = 400

    private const val SEP = "|"
    private const val DATE_SEP = ","

    /**
     * Landesweite Tage ([national]) und die zusätzlichen der Region ([regional]) als Daten «yyyy-MM-dd»,
     * eingeschränkt auf `today` bis vor `today + days`. Unlesbare Einträge fallen weg.
     */
    fun resolve(national: List<String>, regional: List<String>, today: LocalDate, days: Int = SYNC_DAYS): Set<LocalDate> {
        val end = today.plusDays(days.toLong())
        return (national + regional).mapNotNull(::parse).filter { !it.isBefore(today) && it.isBefore(end) }.toSortedSet()
    }

    /** Schreibt «region|yyyy-MM-dd,yyyy-MM-dd» mit aufsteigend sortierten Daten; ohne Daten «region|». */
    fun encode(list: HolidayList): String =
        list.region + SEP + list.dates.sorted().joinToString(DATE_SEP)

    /** Liest [encode] tolerant: leer oder unlesbar ergibt eine leere Liste ohne Region, falsche Daten fallen weg. */
    fun decode(text: String?): HolidayList {
        if (text.isNullOrBlank()) return HolidayList("", emptySet())
        val region = text.substringBefore(SEP, "")
        val dates = text.substringAfter(SEP, "").split(DATE_SEP).mapNotNull(::parse).toSet()
        return HolidayList(region, dates)
    }

    /** Code für [HolidayList.region]: «CH» oder mit Region «CH-BE»; ohne Land leer. */
    fun regionCode(country: String, region: String?): String = when {
        country.isBlank() -> ""
        region.isNullOrBlank() -> country
        else -> "$country-$region"
    }

    private fun parse(text: String): LocalDate? = try {
        LocalDate.parse(text.trim())
    } catch (_: DateTimeParseException) {
        null
    }
}
