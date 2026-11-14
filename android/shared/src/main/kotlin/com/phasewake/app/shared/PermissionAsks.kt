package com.phasewake.app.shared

import android.app.Activity
import android.content.Context

/**
 * Ob Phase Wake ein Recht schon einmal angefragt hat, über das Ende des Prozesses hinaus (Prüfrunde 2, B-6). Android
 * meldet «dauerhaft abgelehnt» nicht direkt: Schon gefragt und keine Begründung erwünscht heisst, das System zeigt keine
 * Anfrage mehr. Galt «gefragt» nur im laufenden Prozess, tat der erste Tipp auf «Erlauben» nach jedem Start sichtbar
 * nichts; erst der zweite öffnete die Einstellungen. Handy und Galaxy Watch gleich; das iPhone liest den Status direkt.
 */
object PermissionAsks {
    private const val FILE = "permission_asks"

    /** Ob [permission] schon angefragt wurde. */
    fun asked(context: Context, permission: String): Boolean = prefs(context).getBoolean(permission, false)

    /** Vor dem Anfragen aufrufen: [permissions] gelten ab jetzt als gefragt. */
    fun markAsked(context: Context, permissions: Collection<String>) {
        val edit = prefs(context).edit()
        permissions.forEach { edit.putBoolean(it, true) }
        edit.apply()
    }

    /**
     * Ob eine Anfrage nach [permission] noch einen Dialog zeigt: noch nie gefragt, oder das System will eine Begründung
     * (einmal abgelehnt). Sonst ist das Recht dauerhaft abgelehnt, und nur die Einstellungen helfen.
     */
    fun canAsk(context: Context, permission: String): Boolean =
        !asked(context, permission) || (context as? Activity)?.shouldShowRequestPermissionRationale(permission) == true

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
