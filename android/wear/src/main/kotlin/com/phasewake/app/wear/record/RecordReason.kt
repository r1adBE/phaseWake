package com.phasewake.app.wear.record

import com.phasewake.app.shared.NightRecording
import com.phasewake.app.shared.RecordEnd

/**
 * Grund fürs Diagnose-Protokoll ([RecordService.reasonText]): «manual watch», «manual phone», «cap 12h», «battery 14%»,
 * «service ended», «wake» (Lernphase).
 */
internal fun recordReasonText(reason: RecordEnd, battery: Int?): String = when (reason) {
    RecordEnd.WATCH -> "manual watch"
    RecordEnd.PHONE -> "manual phone"
    RecordEnd.CAP -> "cap ${NightRecording.MAX_DURATION.toHours()}h"
    RecordEnd.BATTERY -> "battery ${battery ?: "-"}%"
    RecordEnd.SERVICE -> "service ended"
    RecordEnd.WAKE -> "wake"
}
