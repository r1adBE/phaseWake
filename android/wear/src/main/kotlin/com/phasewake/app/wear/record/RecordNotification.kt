package com.phasewake.app.wear.record

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.phasewake.app.shared.RecordEnd
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.ui.shortTime
import com.phasewake.app.wear.wake.Notifications
import java.time.Instant

// Mitteilung von «Nacht aufzeichnen», ausgelagert aus RecordControl.kt.

private const val REQUEST_STOP = 13
private const val REQUEST_OPEN = 14

/** Daueranzeige «Nacht wird aufgezeichnet · Seit 23:10» mit «Aufzeichnung stoppen» und laufender Aktivität. */
fun RecordControl.notification(context: Context, since: Instant): Notification {
    val open = PendingIntent.getActivity(
        context, REQUEST_OPEN, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val stop = PendingIntent.getService(
        context, REQUEST_STOP,
        Intent(context, RecordService::class.java)
            .setAction(RecordService.ACTION_STOP)
            .putExtra(RecordService.EXTRA_REASON, RecordEnd.WATCH.code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val time = shortTime(since)
    val text = context.getString(R.string.record_since, time)
    val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_WINDOW)
        .setSmallIcon(R.drawable.ic_moon)
        .setContentTitle(context.getString(R.string.notification_record_title))
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(open)
        .addAction(R.drawable.ic_moon, context.getString(R.string.record_stop), stop)
    OngoingActivity.Builder(context, Notifications.ID_RECORD, builder)
        .setStaticIcon(R.drawable.ic_moon)
        .setTouchIntent(open)
        .setStatus(Status.Builder().addTemplate(text).build())
        .build()
        .apply(context)
    return builder.build()
}
