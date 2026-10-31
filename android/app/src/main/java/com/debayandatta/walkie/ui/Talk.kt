package com.debayandatta.walkie.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debayandatta.walkie.core.Talk
import com.debayandatta.walkie.core.Walkie
import com.debayandatta.walkie.net.TalkMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TalkScreen() {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val ch = st.selectedChannel
    val me = st.me?.id
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Hollo", fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = { Walkie.setMuted(!st.muted) }, modifier = Modifier.size(48.dp)) {
                Icon(if (st.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (st.muted) "Speaker muted on this phone. Unmute" else "Mute speaker on this phone",
                    tint = if (st.muted) W.amber else W.text)
            }
        }
        // Channel chips
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (st.groupChannels + st.personalChannels).forEach { c ->
                val sel = c.id == ch?.id
                FilterChip(selected = sel, onClick = { Walkie.select(c.id) }, label = { Text(st.title(c)) },
                    leadingIcon = when {
                        c.isPersonal -> ({ Icon(Icons.Filled.Lock, null, Modifier.size(16.dp)) })
                        sel -> ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) })
                        else -> null
                    },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = W.secondaryContainer, selectedLabelColor = W.onSecondaryContainer, selectedLeadingIconColor = W.onSecondaryContainer))
            }
        }
        if (ch != null && ch.isPersonal) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().background(W.secondaryContainer, RoundedCornerShape(16.dp))
                .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = W.onSecondaryContainer, modifier = Modifier.size(18.dp))
                Text("Only ${st.title(ch)} hears you", color = W.onSecondaryContainer, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp).weight(1f))
                TextButton(onClick = { Walkie.backToGroup() }, modifier = Modifier.height(48.dp)) {
                    Text("Back to ${st.returnChannel?.let { st.title(it) } ?: "Family"}", color = W.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Who's on duty — tap someone to talk to them only
        if (ch != null) {
            val others = ch.members
            val onDuty = others.count { if (it.id == me) st.onDuty else it.duty }
            Row(Modifier.padding(16.dp).fillMaxWidth().background(W.surface, RoundedCornerShape(28.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    others.forEachIndexed { i, m ->
                        val speaking = st.remote?.userId == m.id || (m.id == me && st.talk.active)
                        val tappable = m.id != me && !ch.isPersonal && !st.talk.active
                        Box(Modifier.padding(start = if (i == 0) 0.dp else 4.dp)
                            .border(2.5.dp, if (speaking) W.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                            .clickable(enabled = tappable, onClickLabel = "Talk to ${m.name} only") { Walkie.buzz(15); Walkie.talkPrivately(m) }
                            .padding(3.dp)) {
                            Avatar(m, size = 36.dp, dimmed = !m.duty && m.id != me)
                        }
                    }
                }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(if (!st.connected) "Connecting…" else "$onDuty of ${ch.members.size} on duty", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (!ch.isPersonal && ch.members.size > 2) Text("Tap someone to talk to them only", fontSize = 12.sp, color = W.textMuted)
                    val off = ch.members.filter { !it.duty && it.id != me }.map { it.name }
                    if (off.isNotEmpty()) Text("${off.joinToString()} ${if (off.size == 1) "is" else "are"} off", fontSize = 14.sp, color = W.textMuted, maxLines = 1)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val remote = st.remote
            if (remote != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(200.dp).background(W.avatar(remote.userId).first, RoundedCornerShape(76.dp)), contentAlignment = Alignment.Center) {
                        Text(st.member(remote.userId)?.initials ?: remote.name.take(1), color = W.avatar(remote.userId).second, fontSize = 64.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${remote.name} is talking", fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    Text("Busy — wait for ${remote.name} to finish", color = W.textMuted)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    TalkButton(st.talk)
                    if (st.talk is Talk.Talking) {
                        LevelBar(st.level)
                    } else {
                        Text("Double-tap for hands-free · tap again to stop", fontSize = 14.sp, color = W.textMuted, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // Last message
        val last = ch?.let { st.clips[it.id] }?.firstOrNull { it.mode != "sos" }
        if (last != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().background(W.surface, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null, tint = W.primary, modifier = Modifier.size(20.dp))
                Text("Last: ${if (last.userId == me) "You" else last.userName} · ${agoText(last.startedAt.toEpochMilli())} · ${durationText(last.durationMs)}",
                    fontSize = 14.sp, color = W.textMuted, modifier = Modifier.padding(start = 12.dp).weight(1f), maxLines = 1)
                TextButton(onClick = { Walkie.play(last) }, modifier = Modifier.height(44.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, tint = W.primary)
                    Text(if (st.playingClipId == last.id) "Stop" else "Replay", color = W.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Press and hold (after a short 0.3 s delay, so brushing the screen does
 * nothing) to talk; double-tap for hands-free; a tap stops hands-free.
 */
@Composable
fun TalkButton(talk: Talk) {
    val scope = rememberCoroutineScope()
    val lastTap = remember { mutableLongStateOf(0L) }
    val active = talk.active
    val handsfree = talk.mode == TalkMode.HANDSFREE
    val size by animateDpAsState(if (active) 256.dp else 248.dp, label = "size")
    val label = when {
        handsfree -> "Hands-free"
        talk is Talk.Requesting -> "Connecting…"
        active -> "Release to send"
        else -> "Hold to talk"
    }
    Box(
        Modifier.size(size)
            .background(if (active) W.primary else W.primary.copy(alpha = 0.9f), RoundedCornerShape(92.dp))
            .semantics {
                role = Role.Button
                contentDescription = if (handsfree) "Hands-free on. Double-tap to stop" else if (active) "Talking" else "Talk"
                onClick(label = if (active) "Stop" else "Talk hands-free") {
                    if (Walkie.state.value.talk.active) Walkie.endTalk() else Walkie.beginTalk(TalkMode.HANDSFREE)
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val downAt = System.currentTimeMillis()
                    val wasHandsfree = Walkie.state.value.talk.mode == TalkMode.HANDSFREE
                    var holding = false
                    var job: Job? = null
                    if (!wasHandsfree) {
                        job = scope.launch {
                            delay(300)
                            if (!Walkie.state.value.talk.active) holding = Walkie.beginTalk(TalkMode.HOLD)
                        }
                    }
                    waitForUpOrCancellation()
                    job?.cancel()
                    if (holding) {
                        Walkie.endTalk()
                    } else if (System.currentTimeMillis() - downAt < 300) {
                        // A tap.
                        if (wasHandsfree) {
                            Walkie.endTalk(); lastTap.longValue = 0L
                        } else if (System.currentTimeMillis() - lastTap.longValue < 350) {
                            lastTap.longValue = 0L
                            Walkie.beginTalk(TalkMode.HANDSFREE)
                        } else {
                            lastTap.longValue = System.currentTimeMillis()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Mic, null, tint = W.onPrimary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, color = W.onPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (handsfree) Text("Tap to stop", color = W.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LevelBar(level: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(36.dp)) {
        repeat(24) { i ->
            val wobble = (kotlin.math.sin(i * 0.9 + System.currentTimeMillis() / 90.0) + 1) / 2
            val h = 6 + (level * 30 * (0.4 + 0.6 * wobble)).toFloat()
            Box(Modifier.width(4.dp).height(h.dp).background(W.primary, RoundedCornerShape(2.dp)))
        }
    }
}
