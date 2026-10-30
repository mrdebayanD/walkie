package com.debayandatta.walkie.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debayandatta.walkie.core.Phase
import com.debayandatta.walkie.core.Walkie
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    /** An invite link that opened the app (walkie://join?…). */
    private val joinLink = mutableStateOf<String?>(null)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Walkie.updateConnection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            WalkieTheme { App(joinLink.value, onAskPermissions = ::requestBasics) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        if (i.data?.scheme == "walkie") joinLink.value = i.dataString
        i.getStringExtra("sosId")?.let { Walkie.openSos(it) }
    }

    private fun requestBasics() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askPermissions.launch(perms.toTypedArray())
    }
}

@Composable
fun App(joinLink: String?, onAskPermissions: () -> Unit) {
    val st by Walkie.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(W.bg)) {
        when (st.phase) {
            Phase.LOADING -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = W.primary)
                Text(st.error ?: "Connecting…", color = W.textMuted, modifier = Modifier.padding(top = 16.dp))
            }
            Phase.NEEDS_JOIN -> JoinScreen(joinLink)
            Phase.PENDING -> PendingScreen()
            Phase.READY -> {
                LaunchedEffect(Unit) { onAskPermissions() }
                MainTabs()
            }
        }
        st.incomingSos?.let { AlertScreen(it) }
        ErrorSnack(st.error, st.phase == Phase.READY)
    }
}

@Composable
private fun MainTabs() {
    val st by Walkie.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showBatteryGuide by rememberSaveable { mutableStateOf(!Walkie.prefs.batteryGuideDone) }
    Scaffold(
        containerColor = W.bg,
        bottomBar = {
            NavigationBar(containerColor = W.surface) {
                val colors = NavigationBarItemDefaults.colors(indicatorColor = if (tab == 3) Color5C1F19 else W.secondaryContainer)
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Mic, null) }, label = { Text("Talk") }, colors = colors)
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.History, null) }, label = { Text("History") }, colors = colors)
                NavigationBarItem(tab == 2, { tab = 2 }, {
                    BadgedBox(badge = { if (st.pending.isNotEmpty()) Badge { Text("${st.pending.size}") } }) { Icon(Icons.Filled.Groups, null) }
                }, label = { Text("Family") }, colors = colors)
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Shield, null) }, label = { Text("Safety") }, colors = colors)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).statusBarsPadding()) {
            when (tab) {
                0 -> TalkScreen()
                1 -> HistoryScreen()
                2 -> FamilyScreen()
                3 -> SafetyScreen()
            }
        }
    }
    if (showBatteryGuide) BatteryGuide(onDone = { Walkie.prefs.batteryGuideDone = true; showBatteryGuide = false })
}

private val Color5C1F19 = androidx.compose.ui.graphics.Color(0xFF5C1F19)

@Composable
private fun ErrorSnack(msg: String?, show: Boolean) {
    LaunchedEffect(msg) { if (msg != null) { delay(3500); if (Walkie.state.value.error == msg) Walkie.clearError() } }
    AnimatedVisibility(visible = show && msg != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), contentAlignment = Alignment.TopCenter) {
            Text(msg ?: "", color = W.text, modifier = Modifier.background(W.surfaceHigh, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}
