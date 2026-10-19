package dev.cybercore.nightwatch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** CYBERGRID neon, the default Cybercore palette. */
object Grid {
    val bg = Color(0xFF07070D)
    val panel = Color(0xFF0F0F1A)
    val line = Color(0xFF262640)
    val fg = Color(0xFFE6E6F0)
    val muted = Color(0xFF8888A8)
    val cyan = Color(0xFF00F0FF)
    val pink = Color(0xFFFF2BD6)
    val purple = Color(0xFF9B5CFF)
    val green = Color(0xFF39FF88)
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF3860)
}

val Mono = FontFamily(Font(R.font.jetbrains_mono), Font(R.font.jetbrains_mono_bold, FontWeight.Bold))

/** Colour for a unit/stack/alert state word. */
fun stateColor(state: String): Color = when (state.lowercase()) {
    "active", "running", "ok", "info" -> Grid.green
    "failed", "crit", "unknown" -> Grid.red
    "activating", "deactivating", "reloading", "partial", "working", "warn", "auto-restart" -> Grid.amber
    else -> Grid.muted
}

@Composable
fun NightwatchTheme(content: @Composable () -> Unit) {
    val t = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Grid.cyan, onPrimary = Grid.bg,
            secondary = Grid.pink, onSecondary = Grid.bg,
            tertiary = Grid.purple,
            background = Grid.bg, onBackground = Grid.fg,
            surface = Grid.panel, onSurface = Grid.fg,
            surfaceVariant = lerp(Grid.panel, Grid.fg, .06f), onSurfaceVariant = Grid.muted,
            surfaceContainer = Grid.panel, surfaceContainerHigh = lerp(Grid.panel, Grid.fg, .05f),
            surfaceContainerHighest = lerp(Grid.panel, Grid.fg, .08f),
            outline = Grid.line, outlineVariant = Grid.line,
            error = Grid.red,
        ),
        typography = t.copy(
            headlineMedium = t.headlineMedium.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            headlineSmall = t.headlineSmall.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            titleLarge = t.titleLarge.copy(fontFamily = Mono, fontWeight = FontWeight.Bold),
            titleMedium = t.titleMedium.copy(fontFamily = Mono),
            titleSmall = t.titleSmall.copy(fontFamily = Mono),
            bodyLarge = t.bodyLarge.copy(fontFamily = Mono),
            bodyMedium = t.bodyMedium.copy(fontFamily = Mono),
            bodySmall = t.bodySmall.copy(fontFamily = Mono),
            labelLarge = t.labelLarge.copy(fontFamily = Mono),
            labelMedium = t.labelMedium.copy(fontFamily = Mono),
            labelSmall = t.labelSmall.copy(fontFamily = Mono),
        ),
    ) {
        // Text drawn outside a Surface (inside a Panel) would default to
        // black; make the theme's foreground the default everywhere.
        CompositionLocalProvider(LocalContentColor provides Grid.fg, content = content)
    }
}

/** The night grid: a horizon of cyan and pink lines under a dark sky. */
@Composable
fun GridBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Grid.bg)
        drawRect(Brush.radialGradient(listOf(Grid.purple.copy(alpha = .10f), Color.Transparent), center = Offset(size.width * .8f, 0f), radius = size.width))
        drawRect(Brush.radialGradient(listOf(Grid.cyan.copy(alpha = .06f), Color.Transparent), center = Offset(0f, size.height), radius = size.width))
        val horizon = size.height * .74f
        for (i in -6..6) {
            drawLine(Grid.cyan.copy(alpha = .06f), Offset(size.width / 2 + i * 25f, horizon), Offset(size.width / 2 + i * size.width / 4, size.height))
        }
        for (i in 1..9) {
            val y = horizon + (size.height - horizon) * (i * i / 81f)
            drawLine(Grid.pink.copy(alpha = .07f), Offset(0f, y), Offset(size.width, y))
        }
    }
}
