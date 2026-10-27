package com.example.voicetomidi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.voicetomidi.notes.NoteEvent
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
 * Mini piano roll de la voix : une ligne par demi-ton, touches noires grisées
 * comme dans un DAW, nom des notes à gauche.
 *
 * Pendant la prise, il montre les ~4 dernières secondes : la courbe monte quand
 * on chante plus aigu, s'estompe vers le passé, la ligne de la note chantée est
 * en surbrillance et les zones où le bouton était maintenu sont surlignées.
 *
 * Une fois la prise analysée ([takeTrail] non nul), la vue se dézoome pour
 * montrer la mélodie entière : la courbe s'efface au profit des notes
 * trouvées, en barres ([notes]). Dézoomé, le vibrato devenait un zigzag illisible.
 * Pendant l'écoute au piano, une barre verticale suit [playbackPositionMs] et
 * la note jouée s'allume.
 */
@Composable
fun PitchTrail(
    points: List<TrailPoint>,
    modifier: Modifier = Modifier,
    takeTrail: List<TrailPoint>? = null,
    notes: List<NoteEvent> = emptyList(),
    playbackPositionMs: Long? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val blackKeyRow = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val octaveColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val heldColor = accent.copy(alpha = 0.12f)
    val playheadColor = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall

    val shown = takeTrail ?: points

    // 0 = fenêtre glissante de la prise en direct, 1 = vue de toute la prise.
    // Rejoué seulement pour une nouvelle prise (pas quand une option ré-analyse les notes).
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(takeTrail) {
        zoom.snapTo(0f)
        if (takeTrail != null) zoom.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }

    // La vue en direct suit la voix : centrée sur la médiane des derniers points chantés.
    val recent = shown.mapNotNull { it.semitones }.takeLast(40).sorted()
    val target = if (recent.isEmpty()) 60f else recent[recent.size / 2]
    val liveCenter by animateFloatAsState(targetValue = target, animationSpec = tween(400), label = "trailCenter")

    // Cadre de la vue d'ensemble : toute la durée chantée, toute la tessiture (+ marge).
    val overview = remember(takeTrail, notes) {
        val voiced = takeTrail?.filter { it.semitones != null }
        if (voiced.isNullOrEmpty()) return@remember null
        val pitches = notes.map { it.midiNote.toFloat() }.ifEmpty { voiced.map { it.semitones!! } }
        val low = pitches.min()
        val high = pitches.max()
        Overview(
            startMs = voiced.first().timestampMs - OVERVIEW_MARGIN_MS,
            endMs = voiced.last().timestampMs + OVERVIEW_MARGIN_MS,
            center = (low + high) / 2,
            halfRange = maxOf(MIN_OVERVIEW_SEMITONES_EACH_SIDE, (high - low) / 2 + 2f)
        )
    }

    Canvas(modifier.clipToBounds()) {
        val z = if (overview == null) 0f else zoom.value
        val center = lerp(liveCenter, overview?.center ?: liveCenter, z)
        val halfRange = lerp(VISIBLE_SEMITONES_EACH_SIDE, overview?.halfRange ?: VISIBLE_SEMITONES_EACH_SIDE, z)
        // Pendant le dézoom, la note "courante" n'a plus de sens.
        val currentNote = if (z == 0f) shown.lastOrNull()?.semitones?.roundToInt() else null

        drawRect(background)
        val gutter = 40.dp.toPx()
        val rowHeight = size.height / (2 * halfRange)
        fun y(semitones: Float) = size.height / 2 - (semitones - center) * rowHeight

        val lowest = floor(center - halfRange).toInt() - 1
        val highest = ceil(center + halfRange).toInt() + 1
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
        if (shown.isEmpty()) return@Canvas

        clipRect(left = gutter) {
            val liveEnd = shown.last().timestampMs
            val windowStart = lerp((liveEnd - TRAIL_WINDOW_MS).toFloat(), (overview?.startMs ?: 0L).toFloat(), z)
            val windowEnd = lerp(liveEnd.toFloat(), (overview?.endMs ?: liveEnd).toFloat(), z)
            val usableWidth = size.width - gutter - 12.dp.toPx() * z
            fun x(t: Long) = gutter + (t - windowStart) / (windowEnd - windowStart) * usableWidth

            var heldSince: Long? = null
            for (p in shown) {
                if (p.isHeld && heldSince == null) heldSince = p.timestampMs
                if (!p.isHeld && heldSince != null) {
                    drawRect(heldColor, Offset(x(heldSince), 0f), Size(x(p.timestampMs) - x(heldSince), size.height))
                    heldSince = null
                }
            }
            heldSince?.let { drawRect(heldColor, Offset(x(it), 0f), Size(size.width - x(it), size.height)) }

            // Notes trouvées, en barres arrondies comme dans un piano roll : elles apparaissent avec le dézoom.
            if (z > 0f) {
                val barHeight = rowHeight * 0.72f
                val corner = CornerRadius(minOf(barHeight / 2, 4.dp.toPx()))
                for (note in notes) {
                    val isPlaying = playbackPositionMs != null && playbackPositionMs in note.startTimeMs until note.endTimeMs
                    val left = x(note.startTimeMs)
                    // Un mince espace sépare deux notes collées (sinon "Do Do Do" se lit comme une seule note).
                    val width = (x(note.endTimeMs) - left - 1.5.dp.toPx()).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = (if (isPlaying) playheadColor else accent).copy(alpha = 0.9f * z),
                        topLeft = Offset(left, y(note.midiNote.toFloat()) - barHeight / 2),
                        size = Size(width, barHeight),
                        cornerRadius = corner
                    )
                }
            }

            val path = Path()
            var previousMs: Long? = null
            for (p in shown) {
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
            if (playbackPositionMs != null && z > 0f) {
                val playheadX = x(playbackPositionMs)
                drawLine(playheadColor.copy(alpha = z), Offset(playheadX, 0f), Offset(playheadX, size.height), strokeWidth = 2.dp.toPx())
            }

            if (z >= 1f) return@clipRect
            // Le passé s'estompe vers la gauche et un halo donne du relief à la ligne ; le tout s'efface pendant le dézoom.
            val fade = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0f), accent.copy(alpha = 1 - z)),
                startX = gutter,
                endX = size.width
            )
            val glowFade = Brush.horizontalGradient(
                listOf(accent.copy(alpha = 0f), accent.copy(alpha = 0.3f * (1 - z))),
                startX = gutter,
                endX = size.width
            )
            drawPath(path, glowFade, style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, fade, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            shown.last().semitones?.let {
                val dot = Offset(x(shown.last().timestampMs), y(it))
                drawCircle(accent.copy(alpha = 0.35f * (1 - z)), radius = 9.dp.toPx(), center = dot)
                drawCircle(accent.copy(alpha = 1 - z), radius = 5.dp.toPx(), center = dot)
            }
        }
    }
}

/** Cadre de la vue d'ensemble d'une prise : durée affichée et hauteurs visibles. */
private class Overview(val startMs: Long, val endMs: Long, val center: Float, val halfRange: Float)

/** Marge laissée avant la 1re et après la dernière note chantée, en vue d'ensemble. */
private const val OVERVIEW_MARGIN_MS = 300L

/** En vue d'ensemble, au moins une octave visible, même pour une mélodie de 2 notes. */
private const val MIN_OVERVIEW_SEMITONES_EACH_SIDE = 6f

/**
 * Grand pavé à maintenir pendant qu'on chante une note, et à relâcher à la
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

    val scale by animateFloatAsState(targetValue = if (isHolding) 0.97f else 1f, animationSpec = tween(90), label = "holdScale")
    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surface
            isHolding -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(90),
        label = "holdColor"
    )
    val shape = MaterialTheme.shapes.extraLarge

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (isHolding || !enabled) 0f else 0.45f), shape)
            .semantics { contentDescription = "Maintiens pendant la note" }
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
        if (isHolding) {
            Text("♪", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Maintiens pendant la note",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Facultatif · plus précis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Bouton d'enregistrement façon Dictaphone : un rond rouge dans un anneau,
 * qui devient un carré arrondi pendant la prise (= "arrêter").
 */
@Composable
fun RecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val innerSize by animateDpAsState(targetValue = if (isRecording) 30.dp else 62.dp, animationSpec = tween(200), label = "recordSize")
    val innerCorner by animateDpAsState(targetValue = if (isRecording) 7.dp else 31.dp, animationSpec = tween(200), label = "recordCorner")
    val ring = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.9f else 0.3f)
    val inner = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .size(78.dp)
            .border(4.dp, ring, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (isRecording) "Arrêter" else "Enregistrer" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(inner)
        )
    }
}
