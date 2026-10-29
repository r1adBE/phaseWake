package com.phasewake.app.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.phasewake.app.wear.R
import com.phasewake.app.wear.ui.MainActivity
import com.phasewake.app.wear.wearApp
import com.google.common.util.concurrent.ListenableFuture
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/** Kachel: nächstes Weckfenster auf einen Blick; Tippen öffnet die App. */
class NextAlarmTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val zone = ZoneId.systemDefault()
        val plan = wearApp.scheduler.nextPlan()
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        val (title, line) = if (plan == null) {
            getString(R.string.home_no_alarm) to getString(R.string.home_no_alarm_hint)
        } else if (plan.exact) {
            // «Genau»: kein Fenster, kein Versprechen zum leichten Schlaf.
            getString(R.string.window_exact_line, time.format(plan.target.atZone(zone))) to getString(R.string.tile_next)
        } else {
            getString(R.string.home_window, time.format(plan.windowStart.atZone(zone)), time.format(plan.target.atZone(zone))) to
                getString(R.string.tile_light_sleep)
        }
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // Neu zeichnen, sobald sich das Fenster ändern kann (spätestens nach einer Stunde).
            .setFreshnessIntervalMillis(60 * 60 * 1000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(title, line)))
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build() }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun layout(title: String, line: String): LayoutElementBuilders.LayoutElement {
        val open = ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(text(getString(R.string.tile_next), 13f, MIST))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
                    .addContent(text(title, 24f, WHITE, bold = true))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
                    .addContent(text(line, 13f, MUTED))
                    .build(),
            )
            .build()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(2)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val WHITE = 0xFFFBF7F0.toInt()
        const val MIST = 0xFFC9C3E6.toInt()
        const val MUTED = 0xFFA9A3CF.toInt()
    }
}
