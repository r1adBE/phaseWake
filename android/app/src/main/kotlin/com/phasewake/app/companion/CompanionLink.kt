package com.phasewake.app.companion

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.phasewake.app.core.PairedDevice
import java.util.regex.Pattern

/**
 * Phase Wake als Begleit-App der Uhr (CompanionDeviceManager, docs/ENTSCHEIDE.md 29.10.2026). Mit einer Zuordnung
 * erlaubt Android der App, eine Activity aus dem Hintergrund zu starten: Der Weckbildschirm kommt so auch bei
 * benutztem Handy ganz nach vorn, statt nur als Mitteilung oben. Ohne Geräteprofil (keine Rolle «Uhr»): Die hält
 * die App des Herstellers (z. B. Galaxy Wearable), und sie soll unberührt bleiben. Die Zuordnung speichert das
 * System (Adresse der Uhr, nur auf dem Handy); Phase Wake speichert davon nichts.
 */
object CompanionLink {
    private const val TAG = "CompanionLink"

    /** Ob das Gerät Begleit-Apps kennt (Funktion `android.software.companion_device_setup`). */
    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    /** Ob Phase Wake Begleit-App eines Geräts ist; `false` ohne Unterstützung oder bei einem Fehler des Systems. */
    fun isAssociated(context: Context): Boolean {
        if (!supported(context)) return false
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) manager.myAssociations.isNotEmpty() else legacyAssociated(manager)
        } catch (e: RuntimeException) {
            // Z. B. SecurityException auf angepassten Systemen: dann gilt «nicht zugeordnet», die Ersatzwege tragen.
            Log.w(TAG, "Zuordnungen nicht lesbar", e)
            false
        }
    }

    /** Bis Android 12L: Adressen der zugeordneten Geräte (ab 13 ersetzt durch `myAssociations`). */
    @Suppress("DEPRECATION")
    private fun legacyAssociated(manager: CompanionDeviceManager): Boolean = manager.associations.isNotEmpty()

    /**
     * Ob Phase Wake die gekoppelten Geräte lesen darf: ab Android 12 mit dem Recht «Geräte in der Nähe»
     * (`BLUETOOTH_CONNECT`), davor mit `BLUETOOTH` (bei der Installation erteilt).
     */
    fun canReadPaired(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * Gekoppelte Bluetooth-Geräte des Handys, nur um die Uhr für die Anfrage zu wählen (nichts wird gespeichert).
     * Leer ohne Recht, ohne Bluetooth oder bei einem Fehler.
     */
    fun paired(context: Context): List<PairedDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { device ->
                PairedDevice(
                    address = device.address,
                    name = device.alias ?: device.name,
                    wearable = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE,
                )
            }
        } catch (e: SecurityException) {
            // Recht zwischen Prüfung und Aufruf entzogen: dann sucht Android, der Nutzer wählt.
            Log.w(TAG, "Gekoppelte Geräte nicht lesbar", e)
            emptyList()
        }
    }

    /**
     * Anfragen der Reihe nach, bis eine die Uhr findet: mit der Adresse von [device] (Android zeigt die gekoppelte
     * Uhr sofort; ohne Adresse sucht es per Bluetooth, und eine schon verbundene Uhr ist dabei oft nicht sichtbar),
     * dann nach den Namen [names] der verbundenen Uhren, zuletzt ohne Filter (alle Geräte in der Nähe zur Wahl).
     */
    fun requests(device: PairedDevice?, names: List<String>): List<AssociationRequest> = listOfNotNull(
        device?.let { byAddress(it.address) },
        names.takeIf { it.isNotEmpty() }?.let(::byName),
        AssociationRequest.Builder().build(),
    )

    private fun byAddress(address: String): AssociationRequest = AssociationRequest.Builder()
        .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
        .setSingleDevice(true)
        .build()

    private fun byName(names: List<String>): AssociationRequest {
        val builder = AssociationRequest.Builder()
        for (name in names) {
            val pattern = Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE)
            builder.addDeviceFilter(BluetoothDeviceFilter.Builder().setNamePattern(pattern).build())
        }
        return builder.setSingleDevice(names.size == 1).build()
    }
}
