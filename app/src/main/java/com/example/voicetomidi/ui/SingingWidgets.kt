package com.example.voicetomidi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Durée d'historique affichée par [PitchTrail]. */
const val TRAIL_WINDOW_MS = 4000L

/** Un point de la courbe de hauteur en direct : null = silence, [isHeld] = bouton maintenu à cet instant. */
data class TrailPoint(val timestampMs: Long, val semitones: Float?, val isHeld: Boolean)

private const val VISIBLE_SEMITONES_EACH_SIDE = 12f
private val BLACK_KEYS = setOf(1, 3, 6, 8, 10)
private val NATURAL_NAMES = mapOf(0 to "Do", 2 to "Ré", 4 to "Mi", 5 to "Fa", 7 to "Sol", 9 to "La", 11 to "Si")

/**
 * Mini piano roll de la voix en direct (les ~4 dernières secondes) : une
 * ligne par demi-tons sur deux octaves, touches noires grisées comme dans un
 * DAW, nom des notes à gauche, et la ligne de la note chantée en
 * surbrillance. La courbe monte quand on chante plus aigu et s'estompe vers
 * le passé ; les zones où le bouton était maintenu sont surlignées.
 */
@Composable
fun PitchTrail(points: List<TrailPoint>, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val blackKeyRow = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val octaveColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val heldColor = accent.copy(alpha = 0.12f)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    // La vue suit la voix : centrée sur la médiane des derniers points chantés.
    val recent = points.mapNotNull { it.semitones }.takeLast(40).sorted()
    val target = if (recent.isEmpty()) 60f else recent[recent.size / 2]
    val center by animateFloatAsState(targetValue = target, animationSpec = tween(400), label = "trailCenter")
    val currentNote = points.lastOrNull()?.semitones?.roundToInt()

    Canvas(modifier.clipToBounds()) {
        drawRect(background)
        val gutter = 40.dp.toPx()
        val rowHeight = size.height / (2 * VISIBLE_SEMITONES_EACH_SIDE)
        fun y(semitones: Float) = size.height / 2 - (semitones - center) * rowHeight

        val lowest = floor(center - VISIBLE_SEMITONES_EACH_SIDE).toInt() - 1
        val highest = ceil(center + VISIBLE_SEMITONES_EACH_SIDE).toInt() + 1
        for (n in lowest..highest) {
            val top = y(n + 0.5f)
            val isCurrent = n == currentNote
            when {
                isCurrent -> drawRect(accent.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, rowHeight))
                n.mod(12) in BLACK_KEYS -> drawRect(blackKeyRow, Offset(0f, top), Size(size.width, rowHeight))
            }
            val isOctave = n.mod(12) == 0
            drawLine(
                color = if (isOctave) octaveColor else gridColor,
                start = Offset(gutter, top + rowHeight),
                end = Offset(size.width, top + rowHeight),
                strokeWidth = if (isOctave) 1.5f else 1f
            )
            val name = NATURAL_NAMES[n.mod(12)]
            if (name != null && rowHeight >= 9.dp.toPx()) {
                val text = if (isOctave) "$name${n / 12 - 1}" else name
                val layout = textMeasurer.measure(
                    text,
                    labelStyle.copy(
                        color = if (isCurrent) accent else labelColor,
                        fontWeight = if (isCurrent || isOctave) FontWeight.Bold else FontWeight.Normal
                    )
                )
                val labelTop = top + (rowHeight - layout.size.height) / 2
                // Pas d'étiquette coupée à moitié par le bord du cadre.
                if (labelTop >= 0f && labelTop + layout.size.height <= size.height) {
                    drawText(layout, topLeft = Offset(6.dp.toPx(), labelTop))
                }
            }
        }
        drawLine(octaveColor, Offset(gutter, 0f), Offset(gutter, size.height), strokeWidth = 1f)
        if (points.isEmpty()) return@Canvas

        clipRect(left = gutter) {
            val windowStart = points.last().timestampMs - TRAIL_WINDOW_MS
            fun x(t: Long) = gutter + (t - windowStart).toFloat() / TRAIL_WINDOW_MS * (size.width - gutter)

            var heldSince: Long? = null
            for (p in points) {
                if (p.isHeld && heldSince == null) heldSince = p.timestampMs
                if (!p.isHeld && heldSince != null) {
                    drawRect(heldColor, Offset(x(heldSince), 0f), Size(x(p.timestampMs) - x(heldSince), size.height))
                    heldSince = null
                }
            }
            heldSince?.let { drawRect(heldColor, Offset(x(it), 0f), Size(size.width - x(it), size.height)) }

            val path = Path()
            var previousMs: Long? = null
            for (p in points) {
                val s = p.semitones
                if (s == null) {
                    previousMs = null
                    continue
                }
                val previous = previousMs
                if (previous != null && p.timestampMs - previous < 60) path.lineTo(x(p.timestampMs), y(s))
                else path.moveTo(x(p.timestampMs), y(s))
                previousMs = p.timestampMs
            }
            // Le passé s'estompe vers la gauche ; un halo large et transparent donne du relief à la ligne.
            val fade = Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent), startX = gutter, endX = size.width)
            val glowFade = Brush.horizontalGradient(listOf(accent.copy(alpha = 0f), accent.copy(alpha = 0.3f)), startX = gutter, endX = size.width)
            drawPath(path, glowFade, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, fade, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            points.last().semitones?.let {
                val dot = Offset(x(points.last().timestampMs), y(it))
                drawCircle(accent.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = dot)
                drawCircle(accent, radius = 5.dp.toPx(), center = dot)
            }
        }
    }
}

/**
 * Gros bouton à maintenir pendant qu'on chante une note, et à relâcher à la
 * fin de la note. Facultatif : sans appui, l'analyse découpe les notes toute
 * seule.
 */
@Composable
fun HoldToSingButton(
    isHolding: Boolean,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnPressStart by rememberUpdatedState(onPressStart)
    val currentOnPressEnd by rememberUpdatedState(onPressEnd)

    val scale by animateFloatAsState(targetValue = if (isHolding) 0.9f else 1f, animationSpec = tween(90), label = "holdScale")
    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            isHolding -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.secondary
        },
        animationSpec = tween(90),
        label = "holdColor"
    )

    Box(
        modifier = modifier
            .size(140.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RectangleShape)
            .background(fill)
            .border(2.dp, MaterialTheme.colorScheme.primary, RectangleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    currentOnPressStart()
                    try {
                        tryAwaitRelease()
                    } finally {
                        currentOnPressEnd()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isHolding) "♪" else "MAINTIENS\nPENDANT\nLA NOTE",
            style = if (isHolding) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center
        )
    }
}
