package com.example.voicetomidi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.example.voicetomidi.ui.theme.EmoCard
import com.example.voicetomidi.ui.theme.ScreenHeader

/** Les écrans de l'appli. */
enum class Destination { HOME, COMPOSITION, MELODY, DRUMS }

/**
 * Accueil : les trois modes. Mélodie est le mode qui marche aujourd'hui ;
 * Composition rassemble ce qu'on y envoie ; Drums arrive plus tard.
 */
@Composable
fun HomeScreen(trackCount: Int, onOpen: (Destination) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            Text("VoiceToMidi", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Que veux-tu créer ?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ModeCard(
            title = "Composition",
            subtitle = when (trackCount) {
                0 -> "Rassemble tes mélodies et tes rythmes"
                1 -> "1 piste"
                else -> "$trackCount pistes"
            },
            icon = ::drawCompositionIcon,
            onClick = { onOpen(Destination.COMPOSITION) }
        )
        ModeCard(
            title = "Mélodie",
            subtitle = "Chante, l'appli écrit les notes",
            icon = ::drawMelodyIcon,
            highlighted = true,
            onClick = { onOpen(Destination.MELODY) }
        )
        ModeCard(
            title = "Drums",
            subtitle = "Crée tes rythmes",
            icon = ::drawDrumsIcon,
            badge = "Bientôt",
            onClick = { onOpen(Destination.DRUMS) }
        )
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    icon: DrawScope.(Color) -> Unit,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    badge: String? = null
) {
    val accent = MaterialTheme.colorScheme.primary
    val background = if (highlighted) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(MaterialTheme.shapes.large)
            .background(background)
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(30.dp)) { icon(if (badge == null) accent else accent.copy(alpha = 0.45f)) }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                badge?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Petites icônes dessinées (pas de bibliothèque d'icônes dans le projet).

/** Composition : des pistes empilées. */
private fun drawCompositionIcon(scope: DrawScope, color: Color) = with(scope) {
    val barHeight = size.height / 5
    listOf(1f, 0.65f, 0.85f).forEachIndexed { i, length ->
        drawRoundRect(color, Offset(0f, i * barHeight * 1.9f), Size(size.width * length, barHeight), CornerRadius(barHeight / 2))
    }
}

/** Mélodie : des notes qui montent, comme dans le piano roll. */
private fun drawMelodyIcon(scope: DrawScope, color: Color) = with(scope) {
    val barHeight = size.height / 6
    val barWidth = size.width / 3.4f
    listOf(0.75f, 0.45f, 0.15f).forEachIndexed { i, top ->
        drawRoundRect(color, Offset(i * barWidth * 1.2f, size.height * top), Size(barWidth, barHeight), CornerRadius(barHeight / 2))
    }
}

/** Drums : quatre pads. */
private fun drawDrumsIcon(scope: DrawScope, color: Color) = with(scope) {
    val radius = size.width / 4.6f
    for (row in 0..1) for (col in 0..1) {
        drawCircle(color, radius, Offset(size.width * (0.25f + 0.5f * col), size.height * (0.25f + 0.5f * row)))
    }
}

/** Mode Drums : pas encore là. */
@Composable
fun DrumsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        ScreenHeader(title = "Drums", onBack = onBack)
        EmoCard(modifier = Modifier.padding(top = 16.dp)) {
            Text("Bientôt", style = MaterialTheme.typography.titleMedium)
            Text(
                "Ici, tu pourras créer tes rythmes, puis les envoyer dans la composition avec tes mélodies.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
