package com.debayandatta.walkie.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debayandatta.walkie.core.Walkie
import com.debayandatta.walkie.net.Clip
import com.debayandatta.walkie.net.SosEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SafetyScreen() {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var countdown by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }

    Column(Modifier.fillMaxSize()) {
        Text("Safety", fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 20.dp))
        Text("For real emergencies only", fontSize = 14.sp, color = W.textMuted, modifier = Modifier.padding(start = 16.dp))
        Row(Modifier.padding(16.dp).fillMaxWidth().background(W.surface, RoundedCornerShape(24.dp))
            .clickable { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(W.primary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Call, null, tint = W.onPrimary) }
            Column(Modifier.padding(start = 16.dp)) {
                Text("In danger? Call 112 first", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("India's emergency number", fontSize = 14.sp, color = W.textMuted)
            }
        }

        if (st.me?.phone.isNullOrEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(W.amber.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .clickable { profile = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add your phone number so the family can call you during an SOS", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("Add", color = W.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val mine = st.mySos
            if (mine != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                    Text("SOS sent", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = W.error)
                    val n = mine.responders.size
                    Text(if (n == 0) "Alerting ${mine.recipients.size} family members…" else "$n responding: ${mine.responders.joinToString { st.nameOf(it) }}",
                        color = W.textMuted, textAlign = TextAlign.Center)
                    Button(onClick = { Walkie.resolveMySos(false) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary)) { Text("I'm safe", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = { Walkie.resolveMySos(true) }) { Text("It was a false alarm", color = W.textMuted) }
                    CallList("Call someone", skip = null, first = mine.responders)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    HoldRing(progress) {
                        Modifier.semantics {
                            contentDescription = "SOS. Opens a 5-second countdown to alert your family."
                            onClick { countdown = true; true }
                        }.pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                val job = scope.launch {
                                    val start = System.currentTimeMillis()
                                    Walkie.buzz(30)
                                    while (isActive) {
                                        progress = ((System.currentTimeMillis() - start) / 3000f).coerceAtMost(1f)
                                        if (progress >= 1f) { progress = 0f; countdown = true; return@launch }
                                        delay(30)
                                    }
                                }
                                waitForUpOrCancellation()
                                job.cancel()
                                if (!countdown) progress = 0f
                            }
                        }
                    }
                    Text("Press and hold for 3 seconds.\nYou get 5 more seconds to cancel.", fontSize = 14.sp, color = W.textMuted, textAlign = TextAlign.Center)
                }
            }
        }

        if (st.mySos == null) Group(Modifier.padding(bottom = 16.dp)) {
            listOf("Loud alert to all ${maxOf(0, (st.defaultChannel?.members?.size ?: 1) - 1)} family members", "Live location shared", "15-second clip recorded").forEachIndexed { i, t ->
                if (i > 0) Divider()
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(W.error, CircleShape))
                    Text(t, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
    if (countdown) CountdownScreen { countdown = false }
    if (profile) ProfileDialog(null) { profile = false }
}

/** One-tap calls to family members who saved a number (used during an SOS). */
@Composable
fun CallList(title: String, skip: String?, first: List<String>) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val people = st.everyone.filter { it.id != st.me?.id && it.id != skip && !it.phone.isNullOrEmpty() }
        .sortedWith(compareBy({ if (it.id in first) 0 else 1 }, { it.name }))
    if (people.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = W.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().background(W.surface, RoundedCornerShape(24.dp))) {
            people.forEachIndexed { i, m ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Call ${m.name}") { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${m.phone}"))) }
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m, size = 32.dp)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(m.name)
                        if (m.id in first) Text("Responding", fontSize = 12.sp, color = W.primary)
                    }
                    Icon(Icons.Filled.Call, null, tint = W.primary)
                }
            }
        }
    }
}

@Composable
private fun HoldRing(progress: Float, gesture: () -> Modifier) {
    Box(Modifier.size(248.dp).then(gesture()), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(248.dp), color = W.error, trackColor = Color(0xFF3B1D1A), strokeWidth = 14.dp)
        Box(Modifier.size(212.dp).background(W.errorContainer, RoundedCornerShape(86.dp)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SOS", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, color = W.onErrorContainer)
                Text(if (progress > 0f) "Keep holding…" else "Hold 3 seconds", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = W.onErrorContainer)
                if (progress > 0f) Text("%.1f s left".format(3f * (1 - progress)), fontSize = 14.sp, color = W.onErrorContainer.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
fun CountdownScreen(onClose: () -> Unit) {
    var remaining by remember { mutableIntStateOf(5) }
    var sent by remember { mutableStateOf(false) }
    fun send() { if (!sent) { sent = true; Walkie.sendSos(); onClose() } }
    LaunchedEffect(Unit) {
        Walkie.buzz(300)
        while (remaining > 0) { delay(1000); remaining--; Walkie.buzz(40) }
        send()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = true)) {
        Column(Modifier.fillMaxSize().background(W.sosBg).statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            Text("SOS", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB4AE),
                modifier = Modifier.background(W.error.copy(alpha = 0.24f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp))
            Text("Sending SOS to your family", fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            Text("Cancel now if this was a mistake", color = Color(0xCCFFE1DE), modifier = Modifier.padding(top = 8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { remaining / 5f }, modifier = Modifier.size(220.dp), color = W.error, trackColor = Color(0x1AFFFFFF), strokeWidth = 12.dp)
                Text("$remaining", fontSize = 104.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { contentDescription = "$remaining seconds until SOS is sent" })
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xEBFFFFFF), contentColor = Color(0xFF1C1C1E))) { Text("Cancel", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { send() }, modifier = Modifier.padding(top = 12.dp).height(48.dp)) { Text("Send now", color = Color(0xD9FFE1DE), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun AlertScreen(sos: SosEvent) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val sender = st.member(sos.userId)
    var responded by remember(sos.id) { mutableStateOf(false) }
    LaunchedEffect(sos.channelId) { Walkie.loadClips(sos.channelId) }
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Column(Modifier.fillMaxSize().background(W.bg).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().background(if (sos.isActive) Color(0xFFFF453A) else W.primaryContainer, RoundedCornerShape(26.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(sender, name = sos.userName, id = sos.userId, size = 52.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(if (sos.isActive) "SOS · ${agoText(sos.createdAt.toEpochMilli()).uppercase()}" else "SOS CLOSED", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(if (sos.isActive) "${sos.userName} needs help" else if (sos.resolution == "false_alarm") "False alarm" else "${sos.userName} is safe",
                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            val loc = sos.location
            Column(Modifier.fillMaxWidth().background(W.surface, RoundedCornerShape(26.dp)).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(26.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, tint = W.error)
                    Text(if (loc != null) "Updated ${agoText(loc.at.toEpochMilli())} · ±${loc.accuracy.toInt()} m" else "Location not available yet",
                        fontSize = 14.sp, color = W.textMuted, modifier = Modifier.padding(start = 8.dp))
                }
                if (loc != null) {
                    OutlinedButton(onClick = {
                        val uri = Uri.parse("geo:${loc.lat},${loc.lng}?q=${loc.lat},${loc.lng}(${Uri.encode(sos.userName)})")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Filled.Map, null, tint = W.primary); Text("  Open in Maps", color = W.primary)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { sender?.phone?.let { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) } },
                    enabled = !sender?.phone.isNullOrEmpty(), modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary)) {
                    Icon(Icons.Filled.Call, null); Text("  Call ${sos.userName}", fontWeight = FontWeight.Bold, maxLines = 1)
                }
                sos.clipIds.firstOrNull()?.let { clipId ->
                    val clip = st.clips[sos.channelId]?.firstOrNull { it.id == clipId }
                        ?: Clip(clipId, sos.channelId, sos.userId, sos.userName, "sos", sos.id, sos.createdAt, 15000, null)
                    Button(onClick = { Walkie.play(clip) }, modifier = Modifier.weight(1f).height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = W.secondaryContainer, contentColor = W.onSecondaryContainer)) {
                        Icon(Icons.Filled.PlayArrow, null); Text(if (st.playingClipId == clipId) "  Stop" else "  Clip")
                    }
                }
            }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) }, modifier = Modifier.fillMaxWidth().height(56.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, W.error)) { Text("Call 112", color = W.error, fontWeight = FontWeight.Bold) }
            if (sender?.phone.isNullOrEmpty()) {
                Text("${sos.userName} hasn't saved a phone number. Call someone who may be nearby:", fontSize = 13.sp, color = W.textMuted)
            }
            CallList("Call family", skip = sos.userId, first = sos.responders)
            if (sos.responders.isNotEmpty()) {
                Text("${sos.responders.joinToString(" and ") { st.nameOf(it) }} ${if (sos.responders.size == 1) "is" else "are"} responding",
                    color = W.textMuted, modifier = Modifier.fillMaxWidth().background(W.surface, RoundedCornerShape(22.dp)).padding(14.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (sos.isActive) {
                val mine = responded || sos.responders.contains(st.me?.id ?: "")
                Button(onClick = { responded = true; Walkie.respond(sos.id) }, enabled = !mine, modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xEBFFFFFF), contentColor = Color(0xFF1C1C1E))) {
                    Text(if (mine) "You're responding" else "I'm responding", fontWeight = FontWeight.Bold)
                }
            }
            TextButton(onClick = { Walkie.dismissIncoming() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (sos.isActive) "Close (alert stays active)" else "Close", color = W.textMuted)
            }
        }
    }
}
