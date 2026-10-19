package dev.cybercore.nightwatch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
fun Panel(modifier: Modifier = Modifier, accent: Color = Grid.line, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Grid.panel.copy(alpha = .92f))
            .border(BorderStroke(1.dp, accent.copy(alpha = .55f)), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(top = 10.dp, bottom = 2.dp),
        color = Grid.pink,
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 2.sp,
    )
}

@Composable
fun StateDot(state: String, size: Int = 9) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(stateColor(state)))
}

@Composable
fun StatePill(state: String) {
    val c = stateColor(state)
    Text(
        state.ifBlank { "?" },
        Modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = .14f))
            .border(1.dp, c.copy(alpha = .6f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = c,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
    )
}

@Composable
fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Panel(modifier, accent = color, onClick = onClick) {
        Text(value, color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
        Text(label.uppercase(), color = Grid.muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.width(110.dp), color = Grid.muted, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Grid.fg, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun NeonButton(text: String, modifier: Modifier = Modifier, color: Color = Grid.cyan, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = .8f) else Grid.line),
        shape = RoundedCornerShape(8.dp),
    ) { Text(text.uppercase(), color = if (enabled) color else Grid.muted, style = MaterialTheme.typography.labelMedium) }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Grid.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorNote(text: String) {
    Panel(accent = Grid.red) { Text(text, color = Grid.red, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.size(h.dp))

// ── formatting ─────────────────────────────────────────────────────────────

fun bytes(n: Long): String = when {
    n <= 0 -> "—"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.0f KiB".format(n / 1024.0)
    n < 1024L * 1024 * 1024 -> "%.1f MiB".format(n / 1048576.0)
    else -> "%.2f GiB".format(n / 1073741824.0)
}

/** `secs` since the epoch → "3 min ago". */
fun ago(secs: Long?): String {
    if (secs == null || secs <= 0) return "—"
    val d = System.currentTimeMillis() / 1000 - secs
    return when {
        d < 0 -> "in " + span(-d)
        d < 60 -> "just now"
        else -> span(d) + " ago"
    }
}

private fun span(d: Long) = when {
    d < 3600 -> "${d / 60} min"
    d < 86_400 -> "${d / 3600} h"
    else -> "${d / 86_400} d"
}

fun clock(secs: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(secs * 1000))

/** DaemonHall timestamps are microseconds. */
fun microsToSecs(us: Long) = us / 1_000_000
