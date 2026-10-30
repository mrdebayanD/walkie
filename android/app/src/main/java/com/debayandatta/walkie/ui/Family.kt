package com.debayandatta.walkie.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debayandatta.walkie.core.Walkie
import com.debayandatta.walkie.net.Channel
import com.debayandatta.walkie.net.InviteResult
import com.debayandatta.walkie.net.Member
import com.debayandatta.walkie.net.OpenInvite
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

@Composable
fun FamilyScreen() {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Channel?>(null) }
    var creating by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    var ttlMenu by remember { mutableStateOf(false) }
    val people = st.channels.flatMap { it.members }.distinctBy { it.id }.sortedBy { it.name }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalAlignment = Alignment.Bottom) {
                Text("Family", fontSize = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (st.isAdmin) OutlinedButton(onClick = { inviting = true }) {
                    Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp), tint = W.primary); Text("  Add people", color = W.primary)
                }
            }

            if (st.isAdmin && st.pending.isNotEmpty()) {
                SectionTitle("Waiting for you")
                Group {
                    st.pending.forEach { u ->
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u)
                            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                                Text(u.name, fontSize = 16.sp)
                                Text("Joined with link · ${platformName(u)}", fontSize = 14.sp, color = W.textMuted)
                            }
                            TextButton(onClick = { scope.launch { runCatching { Walkie.api?.removeUser(u.id) }; Walkie.loadPending() } }) { Text("Reject", color = W.error) }
                            FilledTonalButton(onClick = { scope.launch { runCatching { Walkie.api?.approve(u.id) }; Walkie.loadPending(); Walkie.loadChannels() } }) { Text("Approve") }
                        }
                    }
                }
            }

            SectionTitle("Channels")
            Group {
                st.channels.forEachIndexed { i, c ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().clickable { if (st.isAdmin) editing = c else Walkie.select(c.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(if (c.isDefault) W.primary else W.channel(c.color).copy(alpha = 0.3f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            if (c.isDefault) Icon(Icons.Filled.Home, null, tint = W.onPrimary) else Text("#", color = W.channel(c.color), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(if (c.isDefault) "Pinned · ${c.members.size} members" else "${c.members.size} members", fontSize = 14.sp, color = W.textMuted)
                        }
                        if (c.isDefault) Text("Default", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = W.onSecondaryContainer,
                            modifier = Modifier.background(W.secondaryContainer, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }

            SectionTitle("Members")
            Group {
                people.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m)
                        Column(Modifier.padding(start = 16.dp).weight(1f)) {
                            Text(if (m.id == st.me?.id) "${m.name} (you)" else m.name, fontSize = 16.sp)
                            Text(platformName(m) + if (m.isAdmin) " · Admin" else "", fontSize = 14.sp, color = W.textMuted)
                        }
                        Text(if (m.duty) "On duty" else if (m.online) "Online" else "Off", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (m.duty) W.primary else W.textMuted)
                        if (st.isAdmin && m.id != st.me?.id) {
                            IconButton(onClick = { scope.launch { runCatching { Walkie.api?.removeUser(m.id) }; Walkie.loadChannels() } }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${m.name}", tint = W.textMuted)
                            }
                        }
                    }
                }
            }

            SectionTitle("This phone")
            Group {
                ToggleRow("On duty", "Hear the family live", st.onDuty) { Walkie.setOnDuty(it) }
                Divider()
                ToggleRow("Low-power mode", "No always-on connection. Messages arrive by push a few seconds late.", st.lowPower) { Walkie.setLowPower(it) }
                Divider()
                ToggleRow("Mute speaker", "Vibrate only, when phones share a room", st.muted) { Walkie.setMuted(it) }
                Divider()
                Box {
                    Row(Modifier.fillMaxWidth().clickable(enabled = st.isAdmin) { ttlMenu = true }.padding(16.dp)) {
                        Text("Delete clips after", fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(ttlLabel(st.settings.clipTtlHours), fontSize = 16.sp, color = W.textMuted)
                    }
                    DropdownMenu(expanded = ttlMenu, onDismissRequest = { ttlMenu = false }) {
                        listOf(1, 6, 12, 24, 48, 72, 168).forEach { h ->
                            DropdownMenuItem(text = { Text(ttlLabel(h)) }, onClick = {
                                ttlMenu = false
                                scope.launch { runCatching { Walkie.api?.setClipTtl(h) }.getOrNull()?.let { Walkie.updateSettingsLocal(it) } }
                            })
                        }
                    }
                }
                Divider()
                Row(Modifier.fillMaxWidth().clickable { profile = true }.padding(16.dp)) { Text("My name and phone number", fontSize = 16.sp) }
            }
            TextButton(onClick = { Walkie.signOut() }, modifier = Modifier.padding(16.dp)) { Text("Leave this family", color = W.error) }
            Text("Connected to ${st.server?.removePrefix("https://") ?: "—"}", fontSize = 12.sp, color = W.textMuted, modifier = Modifier.padding(start = 28.dp, bottom = 96.dp))
        }
        if (st.isAdmin) {
            ExtendedFloatingActionButton(onClick = { creating = true }, containerColor = W.primaryContainer, contentColor = W.onPrimaryContainer,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New channel") })
        }
    }
    if (creating) ChannelEditor(null) { creating = false }
    editing?.let { c -> ChannelEditor(c) { editing = null } }
    if (inviting) InviteDialog(st.defaultChannel?.id) { inviting = false }
    if (profile) ProfileDialog { profile = false }
}

private fun platformName(m: Member) = if (m.platform == "ios") "iPhone" else "Android"
private fun ttlLabel(h: Int) = if (h == 168) "7 days" else "$h hours"

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(2.dp).background(W.bg))

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Text(subtitle, fontSize = 14.sp, color = W.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = W.primary, checkedThumbColor = W.onPrimary))
    }
}

/** Create or edit a channel, choose members, and invite someone new (one screen, as in the mockup). */
@Composable
fun ChannelEditor(channel: Channel?, onClose: () -> Unit) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(channel?.name ?: "") }
    var color by remember { mutableStateOf(channel?.color ?: "blue") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(channel?.members?.map { it.id } ?: listOfNotNull(st.me?.id)) } }
    var busy by remember { mutableStateOf(false) }
    val people = st.channels.flatMap { it.members }.distinctBy { it.id }.sortedBy { it.name }

    FullScreen(onClose) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text(if (channel == null) "New channel" else channel.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Button(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            val api = Walkie.api ?: return@runCatching
                            if (channel == null) api.createChannel(name.trim(), color, selected.toList())
                            else {
                                api.updateChannel(channel.id, name.trim(), if (channel.isDefault) null else color)
                                api.setMembers(channel.id, selected.toList())
                            }
                        }
                        Walkie.loadChannels()
                        busy = false
                        onClose()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary),
            ) { Text(if (channel == null) "Create" else "Save") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding()) {
            OutlinedTextField(name, { name = it }, label = { Text("Channel name") }, singleLine = true, colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().padding(16.dp))
            if (channel?.isDefault != true) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("blue", "green", "orange", "pink", "purple", "teal").forEach { c ->
                        Box(Modifier.size(36.dp).background(W.channel(c), CircleShape)
                            .border(if (color == c) 3.dp else 0.dp, Color.White, CircleShape).clickable { color = c })
                    }
                }
            }
            SectionTitle("Members · ${selected.size} selected")
            Group {
                people.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    val isMe = m.id == st.me?.id
                    Row(Modifier.fillMaxWidth().clickable(enabled = !isMe) { if (m.id in selected) selected.remove(m.id) else selected.add(m.id) }
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m)
                        Text(if (isMe) "${m.name} (you)" else m.name, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp).weight(1f))
                        Checkbox(checked = m.id in selected, enabled = !isMe, onCheckedChange = { if (it) selected.add(m.id) else selected.remove(m.id) },
                            colors = CheckboxDefaults.colors(checkedColor = W.primary, checkmarkColor = W.onPrimary))
                    }
                }
            }
            if (channel != null) {
                SectionTitle("Invite someone new")
                Group { InvitePanel(channel.id) }
                if (!channel.isDefault) {
                    TextButton(onClick = { scope.launch { runCatching { Walkie.api?.deleteChannel(channel.id) }; Walkie.loadChannels(); onClose() } },
                        modifier = Modifier.padding(16.dp)) { Text("Delete channel", color = W.error) }
                }
            } else {
                Text("Create the channel first, then invite new people to it.", fontSize = 13.sp, color = W.textMuted, modifier = Modifier.padding(32.dp, 12.dp))
            }
            Text("Only you can add or remove people. Family stays your default channel.", fontSize = 13.sp, color = W.textMuted, modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun InviteDialog(channelId: String?, onClose: () -> Unit) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    var ch by remember { mutableStateOf(channelId ?: st.defaultChannel?.id) }
    FullScreen(onClose) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            Text("Add people", fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.channels.forEach { c ->
                val sel = c.id == ch
                Text(c.name, color = if (sel) W.onSecondaryContainer else W.textMuted,
                    modifier = Modifier.background(if (sel) W.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                        .border(1.dp, if (sel) Color.Transparent else W.outline, RoundedCornerShape(8.dp)).clickable { ch = c.id }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        ch?.let { id -> androidx.compose.runtime.key(id) { Group { InvitePanel(id) } } }
    }
}

/** Share link, QR code, or text a number. Links are one-use, 24 h, and need your approval. */
@Composable
fun InvitePanel(channelId: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf<InviteResult?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    val open = remember { mutableStateListOf<OpenInvite>() }
    LaunchedEffect(channelId) { runCatching { Walkie.api?.invites() }.getOrNull()?.let { open.clear(); open.addAll(it.filter { i -> i.channelId == channelId }) } }

    suspend fun ensure(): InviteResult? {
        invite?.let { return it }
        val r = runCatching { Walkie.api?.createInvite(channelId, label.trim()) }.getOrNull()
        invite = r
        return r
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(label, { label = it; invite = null }, label = { Text("Who is it for? (optional)") }, singleLine = true,
            colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(modifier = Modifier.weight(1f).height(48.dp), onClick = {
                scope.launch {
                    val inv = ensure() ?: return@launch
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join our family walkie-talkie: ${inv.url}")
                    ctx.startActivity(Intent.createChooser(send, "Share invite"))
                }
            }) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Text("  Share link") }
            FilledTonalButton(modifier = Modifier.weight(1f).height(48.dp), onClick = { scope.launch { if (ensure() != null) showQr = true } }) {
                Icon(Icons.Filled.QrCode, null, Modifier.size(18.dp)); Text("  Show QR")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("+91", color = W.textMuted, modifier = Modifier.border(1.dp, W.outline, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 14.dp))
            OutlinedTextField(phone, { phone = it }, placeholder = { Text("Mobile number") }, singleLine = true, colors = fieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f))
            FilledIconButton(onClick = {
                scope.launch {
                    val inv = ensure() ?: return@launch
                    val digits = phone.filter { it.isDigit() || it == '+' }.let { if (it.startsWith("+")) it else "+91$it" }
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$digits")).putExtra("sms_body", "Join our family walkie-talkie: ${inv.url}"))
                }
            }, enabled = phone.count { it.isDigit() } >= 6, colors = IconButtonDefaults.filledIconButtonColors(containerColor = W.primary, contentColor = W.onPrimary)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send invite by text")
            }
        }
        invite?.let { Text("Code: ${it.code}", fontFamily = FontFamily.Monospace, color = W.textMuted) }
        Text("Links expire in 24 hours. Nobody joins until you approve them.", fontSize = 13.sp, color = W.textMuted)
        open.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Open invite: ${i.label.ifEmpty { "unnamed" }}", fontSize = 14.sp, color = W.textMuted, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { runCatching { Walkie.api?.deleteInvite(i.id) }; open.remove(i) } }) { Text("Delete", color = W.error) }
            }
        }
    }
    if (showQr) invite?.let { inv ->
        Dialog(onDismissRequest = { showQr = false }) {
            Column(Modifier.background(W.surface, RoundedCornerShape(28.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val bmp = remember(inv.url) { qr(inv.url) }
                Image(bmp.asImageBitmap(), contentDescription = "QR code for the invite link",
                    modifier = Modifier.size(240.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(16.dp))
                Text("Scan with the new phone's camera", color = W.textMuted, modifier = Modifier.padding(top = 16.dp))
                TextButton(onClick = { showQr = false }) { Text("Done", color = W.primary) }
            }
        }
    }
}

private fun qr(text: String, size: Int = 512): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun ProfileDialog(onClose: () -> Unit) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(st.me?.name ?: "") }
    var phone by remember { mutableStateOf(st.me?.phone ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.background(W.surface, RoundedCornerShape(28.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("You", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, colors = fieldColors())
            OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, placeholder = { Text("+91 98765 43210") }, singleLine = true,
                colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                supportingText = { Text("Your family can call you from an SOS alert.") })
            err?.let { Text(it, color = W.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Cancel", color = W.textMuted) }
                Button(onClick = {
                    scope.launch {
                        try {
                            Walkie.api?.updateMe(name.trim(), phone.trim())?.let { Walkie.updateMeLocal(it) }
                            Walkie.loadChannels()
                            onClose()
                        } catch (e: Exception) { err = e.message }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary)) { Text("Save") }
            }
        }
    }
}

@Composable
fun FullScreen(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(W.bg).statusBarsPadding()) { content() }
    }
}
