package com.phasewake.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.phasewake.app.R
import com.phasewake.app.core.AlarmSchedule
import com.phasewake.app.core.AlarmSpec
import com.phasewake.app.core.NightRecord
import com.phasewake.app.core.WakePlan
import com.phasewake.app.ui.Format
import com.phasewake.app.ui.components.HeroWave
import com.phasewake.app.ui.components.NightBars
import com.phasewake.app.ui.history.nightSummary
import com.phasewake.app.ui.theme.PhaseWakeTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Startseite: nächstes Weckfenster gross, letzte Nacht, alle Wecker. */
@Composable
fun HomeScreen(
    alarms: List<AlarmSpec>,
    lastNight: NightRecord?,
    needsPermission: Boolean,
    dndBlocks: Boolean,
    onOpenDnd: () -> Unit,
    contentPadding: PaddingValues,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onToggle: (AlarmSpec, Boolean) -> Unit,
) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = Instant.now()
        }
    }
    val zone = ZoneId.systemDefault()
    val plan = remember(alarms, now) { AlarmSchedule.earliestPlan(alarms, now, zone) }

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        item(key = "hero") {
            if (plan != null) {
                HeroCard(plan, alarms.first { it.id == plan.alarmId }, now, zone, onClick = { onEdit(plan.alarmId) })
            } else {
                EmptyHero(onAdd)
            }
        }
        if (needsPermission) {
            item(key = "permission") {
                WarningCard(R.string.home_permission_title, R.string.home_permission_text, R.string.home_permission_action, onRequestPermission)
            }
        }
        if (dndBlocks) {
            item(key = "dnd") { WarningCard(R.string.dnd_title, R.string.dnd_text, R.string.dnd_action, onOpenDnd) }
        }
        if (lastNight != null) {
            item(key = "night") { LastNightCard(lastNight) }
        }
        if (alarms.isNotEmpty()) {
            item(key = "header") {
                Text(
                    stringResource(R.string.home_alarms),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(alarm, Modifier.animateItem(), onClick = { onEdit(alarm.id) }, onToggle = { onToggle(alarm, it) })
            }
        }
        item(key = "spacer") { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun HeroCard(plan: WakePlan, alarm: AlarmSpec, now: Instant, zone: ZoneId, onClick: () -> Unit) {
    val brand = PhaseWakeTheme.brand
    val context = LocalContext.current
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    AnimatedVisibility(appeared, enter = fadeIn() + slideInVertically { it / 6 }) {
        Card(
            onClick = onClick,
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = brand.hero, contentColor = brand.onHero),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp).animateContentSize()) {
                val day = plan.target.atZone(zone).toLocalDate()
                val dayText = stringResource(if (day == LocalDate.now(zone)) R.string.home_next_today else R.string.home_next_tomorrow)
                Text(
                    listOf(dayText, alarm.label).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = brand.onHeroMuted,
                )
                Text(
                    Format.time(plan.windowStart, zone) + " – " + Format.time(plan.target, zone),
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.home_next_in, Format.duration(context, Duration.between(now, plan.windowStart))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = brand.onHeroMuted,
                )
                HeroWave(
                    Modifier.fillMaxWidth().height(72.dp).padding(top = 14.dp),
                    windowFraction = 0.32f,
                )
            }
        }
    }
}

@Composable
private fun EmptyHero(onAdd: () -> Unit) {
    val brand = PhaseWakeTheme.brand
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = brand.hero, contentColor = brand.onHero),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.home_no_alarm_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.home_no_alarm_text),
                style = MaterialTheme.typography.bodyMedium,
                color = brand.onHeroMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            HeroWave(Modifier.fillMaxWidth().height(64.dp).padding(vertical = 12.dp))
            Button(onClick = onAdd) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_add_alarm))
            }
        }
    }
}

@Composable
private fun WarningCard(title: Int, text: Int, action: Int, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(text), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onClick, modifier = Modifier.padding(start = 12.dp)) {
                Text(stringResource(action))
            }
        }
    }
}

@Composable
private fun LastNightCard(night: NightRecord) {
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_last_night), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(Format.time(night.wokeAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            NightBars(
                night.epochs,
                Modifier.fillMaxWidth().height(48.dp).padding(top = 10.dp),
                totalEpochs = (Duration.between(night.windowStart, night.target).toMinutes() * 2).toInt(),
            )
            Text(
                nightSummary(context, night),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AlarmRow(alarm: AlarmSpec, modifier: Modifier, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                val title = alarm.sleepDurationMinutes?.let {
                    stringResource(R.string.alarm_after_sleep, Format.duration(context, Duration.ofMinutes(it.toLong())))
                } ?: Format.window(alarm)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else muted,
                )
                val details = buildList {
                    add(Format.days(context, alarm))
                    if (alarm.sleepDurationMinutes != null) {
                        add(context.getString(R.string.alarm_latest, Format.time(java.time.LocalTime.of(alarm.hour, alarm.minute))))
                    }
                    if (alarm.label.isNotBlank()) add(alarm.label)
                }
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}
