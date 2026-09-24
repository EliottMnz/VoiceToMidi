package com.example.voicetomidi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicetomidi.ui.theme.AppTheme
import com.example.voicetomidi.ui.theme.VoiceToMidiTheme

@Composable
fun ThemePickerScreen(
    modifier: Modifier = Modifier,
    onThemeChosen: (AppTheme) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Choisis un thème",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Tu pourras en changer plus tard.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        AppTheme.entries.forEach { theme ->
            ThemeChoiceCard(theme = theme, onClick = { onThemeChosen(theme) })
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ThemeChoiceCard(theme: AppTheme, onClick: () -> Unit) {
    // Chaque carte s'affiche avec les couleurs du thème qu'elle propose (et pas
    // celles du thème actuellement actif), pour prévisualiser fidèlement le choix.
    VoiceToMidiTheme(appTheme = theme) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeSwatch()
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(theme.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(theme.tagline, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch() {
    Row {
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.tertiary
        ).forEach { color ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}
