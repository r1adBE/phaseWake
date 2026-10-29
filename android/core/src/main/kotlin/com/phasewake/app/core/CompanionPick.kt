package com.phasewake.app.core

/**
 * Ein gekoppeltes Bluetooth-Gerät des Handys, für die Wahl der Uhr ([CompanionPick]). [address]: Bluetooth-Adresse
 * (bleibt auf dem Gerät); [name]: angezeigter Name, `null` = unbekannt; [wearable]: Geräteklasse «Wearable».
 */
data class PairedDevice(val address: String, val name: String?, val wearable: Boolean)

/**
 * Welche gekoppelte Uhr Phase Wake als Begleitgerät anfragt (Android, CompanionDeviceManager). Mit der Adresse
 * zeigt Android die Uhr sofort zur Bestätigung; ohne sucht es per Bluetooth, und eine schon verbundene Uhr ist
 * dabei oft nicht sichtbar. Darum zuerst die gekoppelte Uhr bestimmen. Apple: nur für gleiche Fälle in beiden Kernen.
 */
object CompanionPick {
    /**
     * Gerät aus [devices], dessen Name zu einer verbundenen Uhr aus [nodeNames] (Namen laut Wear OS) passt: zuerst
     * gleich (ohne Gross/Klein, ohne Leerraum am Rand), dann einer im anderen enthalten. Ohne Treffer das einzige
     * Gerät der Klasse «Wearable». Sonst (keines oder mehrere ohne Treffer) `null`: Dann wählt der Nutzer im System.
     */
    fun choose(devices: List<PairedDevice>, nodeNames: List<String>): PairedDevice? {
        val nodes = nodeNames.map(::normalized).filter { it.isNotEmpty() }
        val named = devices.mapNotNull { device -> device.name?.let(::normalized)?.takeIf { it.isNotEmpty() }?.let { device to it } }
        named.firstOrNull { (_, name) -> name in nodes }?.let { return it.first }
        named.firstOrNull { (_, name) -> nodes.any { node -> name.contains(node) || node.contains(name) } }?.let { return it.first }
        return devices.singleOrNull { it.wearable }
    }

    private fun normalized(name: String): String = name.trim().lowercase()
}
