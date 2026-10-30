package com.debayandatta.walkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debayandatta.walkie.core.Walkie
import com.debayandatta.walkie.net.Clip
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen() {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val ch = st.selectedChannel
    var filter by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ch?.id) { ch?.let { Walkie.loadClips(it.id) } }
    val all = ch?.let { st.clips[it.id] } ?: emptyList()
    val missed = all.filter { it.id !in st.played && it.userId != st.me?.id }
    val shown = when (filter) { 1 -> missed; 2 -> all.filter { it.mode == "sos" }; else -> all }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val groups = shown.groupBy {
        when (it.startedAt.atZone(zone).toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> "Earlier"
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("History", fontSize = 32.sp, fontWeight = FontWeight.Medium)
                    Text("Clips delete themselves after ${st.settings.clipTtlHours} hours", fontSize = 14.sp, color = W.textMuted)
                }
                if (missed.isNotEmpty() && ch != null) {
                    Button(onClick = { Walkie.playMissed(ch.id) }, colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary)) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Text(" Play ${missed.size}")
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                listOf("All", "Missed", "SOS").forEachIndexed { i, label ->
                    SegmentedButton(selected = filter == i, onClick = { filter = i }, shape = SegmentedButtonDefaults.itemShape(i, 3),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = W.secondaryContainer, activeContentColor = W.onSecondaryContainer)) { Text(label) }
                }
            }
            if (shown.isEmpty()) {
                Text(if (filter == 1) "You're all caught up." else "No messages in the last ${st.settings.clipTtlHours} hours.",
                    color = W.textMuted, modifier = Modifier.padding(16.dp))
            }
        }
        listOf("Today", "Yesterday", "Earlier").forEach { g ->
            val items = groups[g] ?: return@forEach
            item { SectionTitle(g) }
            item {
                Group {
                    items.forEachIndexed { i, clip ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(2.dp).background(W.bg))
                        ClipRow(clip)
                    }
                }
            }
        }
        item { Box(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ClipRow(clip: Clip) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val playing = st.playingClipId == clip.id
    val unheard = clip.id !in st.played && clip.userId != st.me?.id
    val time = clip.startedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val expiresSoon = clip.expiresAt?.let { it.toEpochMilli() - System.currentTimeMillis() < 3 * 3600_000 } == true
    Column(Modifier.background(if (playing) W.primary.copy(alpha = 0.1f) else W.surface).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(st.member(clip.userId), name = clip.userName, id = clip.userId)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (clip.userId == st.me?.id) "You" else clip.userName, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (clip.mode == "sos") Text("  SOS", color = W.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (unheard) Box(Modifier.padding(start = 8.dp).size(8.dp).background(androidx.compose.ui.graphics.Color(0xFFA6C8FF), CircleShape))
                }
                val hoursLeft = clip.expiresAt?.let { maxOf(1, ((it.toEpochMilli() - System.currentTimeMillis()) / 3600_000).toInt()) }
                Text("$time · ${durationText(clip.durationMs)}" + if (expiresSoon && hoursLeft != null) " · Deletes in $hoursLeft h" else "",
                    fontSize = 14.sp, color = if (expiresSoon) W.amber else W.textMuted)
            }
            FilledIconButton(onClick = { Walkie.play(clip) }, modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (playing) W.primary else W.secondaryContainer, contentColor = if (playing) W.onPrimary else W.onSecondaryContainer)) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play ${clip.userName}'s message")
            }
        }
        if (playing) LinearProgressIndicator(progress = { st.playProgress }, color = W.primary, modifier = Modifier.fillMaxWidth().padding(start = 56.dp, top = 10.dp))
    }
}
