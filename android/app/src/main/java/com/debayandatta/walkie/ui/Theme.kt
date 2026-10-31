package com.debayandatta.walkie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debayandatta.walkie.net.Member
import com.debayandatta.walkie.net.initialsOf

// Material 3 dark scheme in a green tonal palette (matches the mockups).
object W {
    val bg = Color(0xFF101410)
    val surface = Color(0xFF1D211C)
    val surfaceHigh = Color(0xFF272B26)
    val primary = Color(0xFF7FDB8A)
    val onPrimary = Color(0xFF003912)
    val primaryContainer = Color(0xFF005321)
    val onPrimaryContainer = Color(0xFF9BF8A4)
    val secondaryContainer = Color(0xFF3A4A39)
    val onSecondaryContainer = Color(0xFFD5E8D0)
    val text = Color(0xFFE0E4DC)
    val textMuted = Color(0xFFC2C9BD)
    val outline = Color(0xFF8C9388)
    val error = Color(0xFFFFB4AB)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFFFDAD6)
    val sosBg = Color(0xFF3A0907)
    val amber = Color(0xFFFFB95C)

    private val avatarPairs = listOf(
        Color(0xFF5A4400) to Color(0xFFFFDF9E), Color(0xFF3B3A73) to Color(0xFFC4C2FF), Color(0xFF6A1B33) to Color(0xFFFFB1C2),
        Color(0xFF523168) to Color(0xFFEBB8FF), Color(0xFF004F5A) to Color(0xFF9FEFFF), Color(0xFF4A4A00) to Color(0xFFEFEF88),
    )

    fun avatar(id: String): Pair<Color, Color> = avatarPairs[(id.hashCode() and 0x7fffffff) % avatarPairs.size]

    fun channel(color: String): Color = when (color) {
        "green" -> primary
        "orange" -> Color(0xFFFFB95C)
        "pink" -> Color(0xFFFFB1C2)
        "purple" -> Color(0xFFEBB8FF)
        "red" -> error
        "teal" -> Color(0xFF9FEFFF)
        "yellow" -> Color(0xFFEFEF88)
        else -> Color(0xFFA6C8FF)
    }
}

@Composable
fun WalkieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = W.primary, onPrimary = W.onPrimary,
            primaryContainer = W.primaryContainer, onPrimaryContainer = W.onPrimaryContainer,
            secondaryContainer = W.secondaryContainer, onSecondaryContainer = W.onSecondaryContainer,
            background = W.bg, onBackground = W.text, surface = W.bg, onSurface = W.text,
            surfaceVariant = W.surface, onSurfaceVariant = W.textMuted, surfaceContainer = W.surface,
            surfaceContainerHigh = W.surfaceHigh, outline = W.outline,
            error = W.error, errorContainer = W.errorContainer, onErrorContainer = W.onErrorContainer,
        ),
    ) {
        // Screens drawn straight on a background (not inside a Surface/Scaffold)
        // would otherwise get black text on the dark background.
        CompositionLocalProvider(LocalContentColor provides W.text, content = content)
    }
}

@Composable
fun Avatar(member: Member?, name: String = member?.name ?: "", id: String = member?.id ?: name, size: Dp = 40.dp, dimmed: Boolean = false) {
    val (bg, fg) = W.avatar(id)
    Box(
        Modifier.size(size).alpha(if (dimmed) 0.5f else 1f).background(bg, CircleShape)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Text(member?.initials ?: initialsOf(name), color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = W.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
}

/** Rounded group of rows, Material 3 "segmented list" style. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).background(W.surface, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))) { content() }
}

fun durationText(ms: Long): String {
    val s = maxOf(1, Math.round(ms / 1000.0).toInt())
    return "%d:%02d".format(s / 60, s % 60)
}

fun agoText(epochMs: Long): String {
    val m = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} d ago"
    }
}
