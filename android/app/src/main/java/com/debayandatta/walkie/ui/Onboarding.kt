package com.debayandatta.walkie.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debayandatta.walkie.core.Walkie
import kotlinx.coroutines.launch

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = W.primary, unfocusedBorderColor = W.outline, focusedLabelColor = W.primary, cursorColor = W.primary)

@Composable
fun JoinScreen(prefill: String?) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var link by rememberSaveable { mutableStateOf(prefill ?: "") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(prefill) { if (prefill != null) link = prefill }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(Icons.Filled.SettingsInputAntenna, null, tint = W.primary, modifier = Modifier.padding(bottom = 4.dp))
        Text("Join your family", fontSize = 32.sp, fontWeight = FontWeight.Medium)
        Text("Open the invite link you were sent, or paste it here.", color = W.textMuted)
        OutlinedTextField(link, { link = it }, label = { Text("Invite link") }, singleLine = true, colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        if (link.isNotBlank() && Walkie.parseInvite(link, null) == null) {
            OutlinedTextField(code, { code = it }, label = { Text("Invite code") }, singleLine = true, colors = fieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(name, { name = it }, label = { Text("Your name") }, placeholder = { Text("e.g. Ma, Baba, Didi") }, singleLine = true,
            colors = fieldColors(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("This is how the family sees you.") })
        OutlinedTextField(phone, { phone = it }, label = { Text("Your phone number") }, placeholder = { Text("+91 98765 43210") }, singleLine = true,
            colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("So your family can call you if you send an SOS.") })
        st.error?.let { Text(it, color = W.error) }
        Button(
            onClick = { busy = true; scope.launch { Walkie.join(link, code, name.trim(), phone.trim()); busy = false } },
            enabled = !busy && link.isNotBlank() && name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary),
        ) {
            if (busy) CircularProgressIndicator(color = W.onPrimary, strokeWidth = 2.dp, modifier = Modifier.height(20.dp)) else Text("Join", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun PendingScreen() {
    val st by Walkie.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.HourglassTop, null, tint = W.amber)
        Text("Waiting for approval", fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
        Text("Hi ${st.me?.name ?: ""}. The family admin needs to approve you. This screen updates by itself.",
            color = W.textMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        CircularProgressIndicator(color = W.primary, modifier = Modifier.padding(top = 24.dp))
        Spacer(Modifier.height(48.dp))
        TextButton(onClick = { Walkie.signOut() }) { Text("Use a different invite", color = W.error) }
    }
}

/**
 * Battery managers on many Android phones (Xiaomi/Redmi, Vivo, Oppo, Realme,
 * OnePlus, Samsung) stop background apps. Without these settings, messages
 * can arrive late or only when the app is opened.
 */
@SuppressLint("BatteryLife")
@Composable
fun BatteryGuide(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val brand = Build.MANUFACTURER.lowercase()
    val pm = ctx.getSystemService(PowerManager::class.java)
    val exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val brandSteps = when {
        "xiaomi" in brand || "redmi" in brand || "poco" in brand -> "Settings › Apps › Hollo › Autostart: ON, and Battery saver: No restrictions."
        "vivo" in brand || "iqoo" in brand -> "Settings › Battery › Background power consumption › Hollo: Allow. Also allow Auto-start."
        "oppo" in brand || "realme" in brand || "oneplus" in brand -> "Settings › Apps › Hollo › Battery usage: Allow background activity and Auto launch."
        "samsung" in brand -> "Settings › Apps › Hollo › Battery: Unrestricted. Also remove Hollo from Sleeping apps."
        "huawei" in brand || "honor" in brand -> "Settings › Battery › App launch › Hollo: Manage manually, turn everything on."
        else -> "Settings › Apps › Hollo › Battery: Unrestricted."
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(20.dp).background(W.surface, androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Keep Hollo listening", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("Your phone may stop Hollo in the background to save battery. Hollo itself uses almost none while idle.", color = W.textMuted)
            Text("1. Allow Hollo to run unrestricted", fontWeight = FontWeight.SemiBold)
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                },
                enabled = !exempt,
                colors = ButtonDefaults.buttonColors(containerColor = W.secondaryContainer, contentColor = W.onSecondaryContainer),
            ) { Text(if (exempt) "Done" else "Allow") }
            Text("2. On this phone (${Build.MANUFACTURER})", fontWeight = FontWeight.SemiBold)
            Text(brandSteps, color = W.textMuted)
            TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
            }) { Text("Open Hollo's settings", color = W.primary) }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = W.primary, contentColor = W.onPrimary)) { Text("I've done this") }
        }
    }
}
