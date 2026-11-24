package com.example.voicetomidi.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.voicetomidi.notes.TapTempo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import com.example.voicetomidi.project.MIN_PROJECT_BPM
import com.example.voicetomidi.project.MAX_PROJECT_BPM
import com.example.voicetomidi.ui.theme.EmoCard
import com.example.voicetomidi.ui.theme.EmoOutlinedButton
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Petite molette de tempo et action de calage, dans le thème du projet. */
@Composable
fun TempoCard(bpm: Int, onTempoChange: (Int) -> Unit, onAlign: () -> Unit,
              canAlign: Boolean, enabled: Boolean = true) {
    var showTapTempo by remember { mutableStateOf(false) }
    EmoCard {
        Text("Tempo du projet", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TempoDial(bpm, onTempoChange, enabled)
            EmoOutlinedButton(
                text = "Tap\ntempo",
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                textAlign = TextAlign.Center,
                onClick = { showTapTempo = true },
                enabled = enabled,
                modifier = Modifier.width(80.dp)
            )
            EmoOutlinedButton(text = "Ajuster au tempo choisi", onClick = onAlign,
                enabled = enabled && canAlign, modifier = Modifier.weight(1f))
        }
        Text("Tape la pulsation sur « Tap tempo » ou glisse la molette. Ajuster cale les notes sur la grille.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showTapTempo && enabled) {
        TapTempoDialog(bpm = bpm, onTempoChange = onTempoChange, onDismiss = { showTapTempo = false })
    }
}

/** Une grande cible de frappe dans un panneau compact ; ouvrir le panneau ne compte pas comme un tap. */
@Composable
private fun TapTempoDialog(bpm: Int, onTempoChange: (Int) -> Unit, onDismiss: () -> Unit) {
    val taps = remember { TapTempo() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Tap tempo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Fermer") }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                        .clickable(role = Role.Button, onClickLabel = "Taper la pulsation") {
                            taps.tap(SystemClock.uptimeMillis())?.let(onTempoChange)
                        }
                        .semantics { contentDescription = "Taper la pulsation" },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$bpm BPM", style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Tape ici", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Text("Tape régulièrement. Le tempo s’applique en direct dès le deuxième tap.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Geste vertical précis : 3 dp par BPM, sans interrompre le glissement lorsque le tempo change. */
@Composable
private fun TempoDial(bpm: Int, onTempoChange: (Int) -> Unit, enabled: Boolean) {
    val currentBpm by rememberUpdatedState(bpm)
    val currentOnChange by rememberUpdatedState(onTempoChange)
    val pixelsPerBpm = with(LocalDensity.current) { 3.dp.toPx() }
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier.size(72.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Tempo du projet en BPM"
                progressBarRangeInfo = ProgressBarRangeInfo(bpm.toFloat(),
                    MIN_PROJECT_BPM.toFloat()..MAX_PROJECT_BPM.toFloat(), MAX_PROJECT_BPM - MIN_PROJECT_BPM - 1)
                if (!enabled) disabled()
                setProgress { value ->
                    if (enabled && value.isFinite()) {
                        currentOnChange(value.roundToInt().coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM))
                        true
                    } else false
                }
            }
            .pointerInput(enabled, pixelsPerBpm) {
                if (!enabled) return@pointerInput
                var value = currentBpm.toFloat()
                detectDragGestures(
                    onDragStart = { value = currentBpm.toFloat() },
                    onDrag = { change, amount ->
                        change.consume()
                        value = (value - amount.y / pixelsPerBpm).coerceIn(MIN_PROJECT_BPM.toFloat(), MAX_PROJECT_BPM.toFloat())
                        val next = value.roundToInt()
                        if (next != currentBpm) currentOnChange(next)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(68.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val fraction = (bpm - MIN_PROJECT_BPM).toFloat() / (MAX_PROJECT_BPM - MIN_PROJECT_BPM)
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(rail, 135f, 270f, false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(if (enabled) accent else foreground, 135f, 270f * fraction, false,
                Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((135 + 270 * fraction).toDouble())
            val radius = size.minDimension / 2 - 7.dp.toPx()
            val end = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
            val start = Offset(center.x + cos(angle).toFloat() * (radius - 5.dp.toPx()),
                center.y + sin(angle).toFloat() * (radius - 5.dp.toPx()))
            drawLine(if (enabled) accent else foreground, start, end, 2.dp.toPx(), StrokeCap.Round)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bpm.toString(), style = MaterialTheme.typography.titleMedium, color = foreground)
            Text("BPM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
