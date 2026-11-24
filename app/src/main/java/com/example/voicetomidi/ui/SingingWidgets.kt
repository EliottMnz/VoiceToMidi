package com.example.voicetomidi.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.NoteGrab
import com.example.voicetomidi.notes.editedNote
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
 *
 * En vue d'ensemble, le graphique se manipule au doigt : toucher une note
 * appelle [onNoteTap] (pour la changer), toucher ailleurs place le curseur de
 * lecture ([onSeek]) et le faire glisser le déplace ([onScrub], [onScrubEnd]).
 * Si on la décale ou l'allonge dans la loupe ([NoteTimingEditor]), elle est
 * dessinée à [previewTiming].
 * Le curseur suit [playbackPositionMs] pendant l'écoute au piano, sinon il
 * reste sur [cursorMs] ; la note jouée s'allume. Pendant qu'on choisit une
 * note, elle est dessinée à [previewPitch] et les autres s'estompent.
 */
@Composable
fun PitchTrail(
    points: List<TrailPoint>,
    modifier: Modifier = Modifier,
    takeTrail: List<TrailPoint>? = null,
    notes: List<NoteEvent> = emptyList(),
    playbackPositionMs: Long? = null,
    cursorMs: Long? = null,
    selectedNoteIndex: Int? = null,
    previewPitch: Int? = null,
    onNoteTap: ((Int) -> Unit)? = null,
    onSeek: ((Long) -> Unit)? = null,
    onScrub: ((Long) -> Unit)? = null,
    onScrubEnd: (() -> Unit)? = null,
    previewTiming: NoteEvent? = null,
    /** Notes ajoutées par les accords : dessinées plus pâles, on ne les touche pas. */
    chordNotes: List<NoteEvent> = emptyList(),
    /** Notes choisies pour un accord : elles ressortent en blanc. */
    highlightedNotes: Set<Int> = emptySet()
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

    // Cadre de la vue d'ensemble : toute la durée chantée, toute la tessiture (+ marge),
    // élargi si la roue des notes vise plus haut ou plus bas que la mélodie.
    val overview = remember(takeTrail, notes, previewPitch, chordNotes) {
        val voiced = takeTrail?.filter { it.semitones != null }
        if (voiced.isNullOrEmpty()) return@remember null
        val pitches = notes.map { it.midiNote.toFloat() }.ifEmpty { voiced.map { it.semitones!! } } +
            chordNotes.map { it.midiNote.toFloat() } +
            listOfNotNull(previewPitch?.toFloat())
        val low = pitches.min()
        val high = pitches.max()
        Overview(
            startMs = voiced.first().timestampMs - OVERVIEW_MARGIN_MS,
            endMs = voiced.last().timestampMs + OVERVIEW_MARGIN_MS,
            center = (low + high) / 2,
            halfRange = maxOf(MIN_OVERVIEW_SEMITONES_EACH_SIDE, (high - low) / 2 + 2f)
        )
    }

    val currentOnNoteTap by rememberUpdatedState(onNoteTap)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val currentNotes by rememberUpdatedState(notes)
    // Les gestes ne servent qu'en vue d'ensemble, une fois le dézoom terminé.
    val interactive = overview != null && onSeek != null
    val gestures = if (!interactive || overview == null) Modifier else Modifier
        .pointerInput(overview) {
            detectTapGestures { pos ->
                if (zoom.value < 1f) return@detectTapGestures
                val geometry = OverviewGeometry(overview, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx(), 12.dp.toPx())
                val hit = geometry.noteAt(currentNotes, pos, slopPx = 12.dp.toPx(), minHalfHeightPx = 16.dp.toPx())
                if (hit != null) currentOnNoteTap?.invoke(hit) else currentOnSeek?.invoke(geometry.timeAt(pos.x))
            }
        }
        .pointerInput(overview) {
            detectHorizontalDragGestures(
                onDragStart = { pos ->
                    if (zoom.value >= 1f) {
                        val geometry = OverviewGeometry(overview, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx(), 12.dp.toPx())
                        currentOnScrub?.invoke(geometry.timeAt(pos.x))
                    }
                },
                onDragEnd = { currentOnScrubEnd?.invoke() },
                onDragCancel = { currentOnScrubEnd?.invoke() },
                onHorizontalDrag = { change, _ ->
                    if (zoom.value >= 1f) {
                        change.consume()
                        val geometry = OverviewGeometry(overview, size.width.toFloat(), size.height.toFloat(), 40.dp.toPx(), 12.dp.toPx())
                        currentOnScrub?.invoke(geometry.timeAt(change.position.x))
                    }
                }
            )
        }

    // Une fois dézoomé, le cadre glisse en douceur quand il s'élargit (au lieu de sauter).
    val follow = if (zoom.value >= 1f) tween<Float>(250) else snap()
    val overviewCenter by animateFloatAsState(overview?.center ?: liveCenter, follow, label = "overviewCenter")
    val overviewHalfRange by animateFloatAsState(overview?.halfRange ?: VISIBLE_SEMITONES_EACH_SIDE, follow, label = "overviewRange")

    Canvas(modifier.clipToBounds().then(gestures)) {
        val z = if (overview == null) 0f else zoom.value
        val center = lerp(liveCenter, overviewCenter, z)
        val halfRange = lerp(VISIBLE_SEMITONES_EACH_SIDE, overviewHalfRange, z)
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
                // Les notes des accords d'abord, plus pâles : la mélodie reste lisible par-dessus.
                for (note in chordNotes) {
                    val left = x(note.startTimeMs)
                    drawRoundRect(
                        color = accent.copy(alpha = 0.4f * z * if (selectedNoteIndex != null) 0.5f else 1f),
                        topLeft = Offset(left, y(note.midiNote.toFloat()) - barHeight / 2),
                        size = Size((x(note.endTimeMs) - left - 1.5.dp.toPx()).coerceAtLeast(2.dp.toPx()), barHeight),
                        cornerRadius = corner
                    )
                }
                notes.forEachIndexed { index, shownNote ->
                    val isSelected = index == selectedNoteIndex
                    val note = if (isSelected && previewTiming != null) previewTiming else shownNote
                    val isPlaying = playbackPositionMs != null && playbackPositionMs in note.startTimeMs until note.endTimeMs
                    val pitch = if (isSelected && previewPitch != null) previewPitch else note.midiNote
                    // Une note en cours de choix ressort : les autres s'estompent.
                    val alpha = if (selectedNoteIndex != null && !isSelected) 0.35f else 0.9f
                    val left = x(note.startTimeMs)
                    // Un mince espace sépare deux notes collées (sinon "Do Do Do" se lit comme une seule note).
                    val width = (x(note.endTimeMs) - left - 1.5.dp.toPx()).coerceAtLeast(2.dp.toPx())
                    val isHighlighted = isSelected || index in highlightedNotes
                    drawRoundRect(
                        color = (if (isPlaying || isHighlighted) playheadColor else accent).copy(alpha = alpha * z),
                        topLeft = Offset(left, y(pitch.toFloat()) - barHeight / 2),
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
            // Curseur de lecture, avec une poignée ronde qui montre qu'on peut le faire glisser.
            // Sans position choisie, il attend juste avant la 1re note.
            val cursor = playbackPositionMs ?: cursorMs
                ?: notes.minOfOrNull { it.startTimeMs - CURSOR_LEAD_IN_MS }.takeIf { interactive }
            if (cursor != null && z > 0f) {
                val cursorX = x(cursor)
                val cursorAlpha = z * if (playbackPositionMs != null) 1f else 0.75f
                drawLine(playheadColor.copy(alpha = cursorAlpha), Offset(cursorX, 0f), Offset(cursorX, size.height), strokeWidth = 2.dp.toPx())
                drawCircle(playheadColor.copy(alpha = cursorAlpha), radius = 6.dp.toPx(), center = Offset(cursorX, 8.dp.toPx()))
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

/**
 * Positions à l'écran de la vue d'ensemble (dézoom terminé), pour savoir ce
 * que le doigt touche. Mêmes formules que le dessin de [PitchTrail] à zoom = 1.
 */
private class OverviewGeometry(
    private val overview: Overview,
    private val width: Float,
    private val height: Float,
    private val gutter: Float,
    rightMargin: Float
) {
    private val usableWidth = width - gutter - rightMargin
    private val rowHeight = height / (2 * overview.halfRange)

    fun x(t: Long) = gutter + (t - overview.startMs).toFloat() / (overview.endMs - overview.startMs) * usableWidth
    fun y(semitones: Float) = height / 2 - (semitones - overview.center) * rowHeight

    fun timeAt(xPx: Float): Long =
        (overview.startMs + (xPx - gutter) / usableWidth * (overview.endMs - overview.startMs)).toLong()
            .coerceIn(overview.startMs, overview.endMs)
    /** Index de la note sous le doigt (la plus proche, avec une marge : les barres sont fines), ou null. */
    fun noteAt(notes: List<NoteEvent>, pos: Offset, slopPx: Float, minHalfHeightPx: Float): Int? {
        val halfHeight = maxOf(rowHeight * 0.8f, minHalfHeightPx)
        return notes.indices
            .filter { i ->
                val note = notes[i]
                pos.x in (x(note.startTimeMs) - slopPx)..(x(note.endTimeMs) + slopPx) &&
                    abs(pos.y - y(note.midiNote.toFloat())) <= halfHeight
            }
            .minByOrNull { i ->
                val note = notes[i]
                val dx = maxOf(0f, x(note.startTimeMs) - pos.x, pos.x - x(note.endTimeMs))
                dx + abs(pos.y - y(note.midiNote.toFloat()))
            }
    }
}

/**
 * Loupe sur une note, pour la décaler ou l'allonger au doigt : dans le piano
 * roll, une note ne fait que quelques millimètres, trop peu pour attraper son
 * bord. Ici elle occupe un tiers de la largeur, avec ses voisines en gris.
 *
 * Faire glisser la note la décale ; tirer une des deux poignées rondes déplace
 * son début ou sa fin. La note s'arrête contre ses voisines et contre les bords
 * de la loupe ([editedNote]). [edited] est la note telle qu'elle est en ce
 * moment, [onChange] reçoit chaque nouvelle position. Le contour pointillé
 * montre où elle était au départ.
 */
@Composable
fun NoteTimingEditor(
    notes: List<NoteEvent>,
    index: Int,
    edited: NoteEvent,
    onChange: (NoteEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surface
    val neighbourColor = MaterialTheme.colorScheme.surfaceVariant
    val ghostColor = MaterialTheme.colorScheme.onSurfaceVariant
    val handleColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    val original = notes[index]
    // Cadre fixe pendant l'édition, pour que la note reste sous le doigt.
    val windowStart = remember(original) {
        (original.startTimeMs + original.endTimeMs) / 2 - maxOf(original.durationMs * 3, MIN_LOUPE_MS) / 2
    }
    val windowEnd = remember(original) { windowStart + maxOf(original.durationMs * 3, MIN_LOUPE_MS) }

    val currentEdited by rememberUpdatedState(edited)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentNotes by rememberUpdatedState(notes)

    Canvas(
        modifier
            .clipToBounds()
            .semantics { contentDescription = "Loupe sur la note : glisse-la pour la décaler, tire les ronds pour l'allonger" }
            .pointerInput(windowStart, windowEnd, index) {
                val padding = LOUPE_PADDING.toPx()
                val usable = size.width - 2 * padding
                fun x(t: Long) = padding + (t - windowStart).toFloat() / (windowEnd - windowStart) * usable
                val touchRadius = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val note = currentEdited
                    val left = x(note.startTimeMs)
                    val right = x(note.endTimeMs)
                    val toLeft = abs(down.position.x - left)
                    val toRight = abs(down.position.x - right)
                    val grab = when {
                        toLeft <= touchRadius && toLeft <= toRight -> NoteGrab.START
                        toRight <= touchRadius -> NoteGrab.END
                        down.position.x in left..right -> NoteGrab.MOVE
                        else -> return@awaitEachGesture
                    }
                    // Seul un geste horizontal est pris : glisser vers le bas ferme toujours le panneau.
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    val base = currentNotes.toMutableList().also { it[index] = note }
                    fun follow(px: Float) {
                        val deltaMs = ((px - down.position.x) / usable * (windowEnd - windowStart)).roundToLong()
                        currentOnChange(
                            editedNote(base, index, grab, deltaMs, deltaSemitones = 0, minMs = maxOf(0L, windowStart), maxMs = windowEnd)
                        )
                    }
                    follow(start.position.x)
                    horizontalDrag(start.id) { change ->
                        change.consume()
                        follow(change.position.x)
                    }
                }
            }
    ) {
        drawRect(background)
        val padding = LOUPE_PADDING.toPx()
        val usable = size.width - 2 * padding
        fun x(t: Long) = padding + (t - windowStart).toFloat() / (windowEnd - windowStart) * usable
        val barHeight = 44.dp.toPx()
        val top = (size.height - barHeight) / 2
        val corner = CornerRadius(12.dp.toPx())

        // Repères tous les dixièmes de seconde, plus marqués chaque seconde.
        var tick = (windowStart / 100 + 1) * 100
        while (tick < windowEnd) {
            val isSecond = tick % 1000 == 0L
            drawLine(
                gridColor.copy(alpha = if (isSecond) 0.9f else 0.35f),
                Offset(x(tick), 0f),
                Offset(x(tick), size.height),
                strokeWidth = if (isSecond) 1.5f else 1f
            )
            tick += 100
        }

        notes.forEachIndexed { i, note ->
            if (i == index || note.endTimeMs < windowStart || note.startTimeMs > windowEnd) return@forEachIndexed
            drawRoundRect(neighbourColor, Offset(x(note.startTimeMs), top), Size(x(note.endTimeMs) - x(note.startTimeMs), barHeight), corner)
        }

        if (edited != original) {
            drawRoundRect(
                ghostColor.copy(alpha = 0.6f),
                Offset(x(original.startTimeMs), top),
                Size(x(original.endTimeMs) - x(original.startTimeMs), barHeight),
                corner,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            )
        }

        val left = x(edited.startTimeMs)
        val right = x(edited.endTimeMs)
        drawRoundRect(accent, Offset(left, top), Size(right - left, barHeight), corner)

        // Poignées : un rond clair à chaque bord, avec un trait qui invite à tirer.
        val handleRadius = 14.dp.toPx()
        for (edgeX in listOf(left, right)) {
            val center = Offset(edgeX, size.height / 2)
            drawCircle(handleColor, radius = handleRadius, center = center)
            drawLine(accent, center - Offset(0f, 6.dp.toPx()), center + Offset(0f, 6.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/**
 * Force d'une note, de 0 à 100 % : une grande barre qu'on remplit au doigt.
 * Toucher la place directement, glisser l'ajuste par pas de [VELOCITY_STEP] %,
 * avec un petit « clic » de vibration à chaque pas (plus marqué tous les
 * 25 %). Au lâcher, [onRelease] permet de faire entendre la note à cette force.
 */
@Composable
fun VelocitySlider(
    percent: Int,
    onChange: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surface
    val tickColor = MaterialTheme.colorScheme.outline
    val haptics = LocalHapticFeedback.current
    val currentPercent by rememberUpdatedState(percent)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val fill by animateFloatAsState(percent / 100f, tween(90), label = "velocityFill")

    Box(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .semantics { contentDescription = "Force de la note : $percent %" }
            .pointerInput(Unit) {
                fun percentAt(x: Float): Int =
                    ((x / size.width * 100f / VELOCITY_STEP).roundToInt() * VELOCITY_STEP).coerceIn(0, 100)
                fun set(value: Int) {
                    if (value == currentPercent) return
                    haptics.performHapticFeedback(
                        if (value % 25 == 0) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick
                    )
                    currentOnChange(value)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var value = percentAt(down.position.x)
                    set(value)
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (start != null) {
                        value = percentAt(start.position.x)
                        set(value)
                        horizontalDrag(start.id) { change ->
                            change.consume()
                            value = percentAt(change.position.x)
                            set(value)
                        }
                    }
                    currentOnRelease(value)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(track)
            drawRect(accent, size = Size(size.width * fill, size.height))
            // Repères tous les 25 %, comme des crans.
            for (mark in listOf(0.25f, 0.5f, 0.75f)) {
                drawLine(
                    tickColor.copy(alpha = if (mark < fill) 0.35f else 0.8f),
                    Offset(size.width * mark, size.height * 0.3f),
                    Offset(size.width * mark, size.height * 0.7f),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
        Text("$percent %", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Pas de la barre de force : assez fin pour nuancer, assez gros pour qu'on sente chaque cran. */
private const val VELOCITY_STEP = 5

/** La loupe montre au moins cette durée, pour qu'une note très courte ait de la place pour s'allonger. */
private const val MIN_LOUPE_MS = 1500L

/** Marge à gauche et à droite de la loupe, pour que les poignées ne soient jamais coupées. */
private val LOUPE_PADDING = 20.dp

/** Sans position choisie, le curseur attend un peu avant la 1re note (comme l'écoute). */
private const val CURSOR_LEAD_IN_MS = 150L

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
