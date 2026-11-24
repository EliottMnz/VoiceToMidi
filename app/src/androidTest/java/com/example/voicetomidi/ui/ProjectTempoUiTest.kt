package com.example.voicetomidi.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.ui.theme.VoiceToMidiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Vérifie le vrai flux audio, les commandes Compose et la relecture du stockage Android. */
class ProjectTempoUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tempoCalageLectureEtSauvegarde() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "tempo-test-${System.nanoTime()}").apply { mkdirs() }
        val projects = ProjectsViewModel()
        projects.attach(directory, directory)
        val id = projects.createProject("Tempo test", 120)
        projects.addMelody(id, listOf(NoteEvent(69, 144, 30000, 100)), 120)
        compose.setContent {
            VoiceToMidiTheme { Surface(modifier = Modifier.fillMaxSize()) { ProjectScreen(id, projects, onBack = {}, onAddMelody = {}) } }
        }
        try {
            compose.onNodeWithContentDescription("Tempo du projet en BPM").performSemanticsAction(SemanticsActions.SetProgress) { it(90f) }
            compose.runOnIdle { assertEquals(90, projects.project(id)!!.bpm) }
            compose.onNodeWithText("Ajuster au tempo choisi").performClick()
            compose.runOnIdle { assertEquals(125L, projects.project(id)!!.tracks.single().notes.single().startTimeMs) }
            // L'horloge audio reste réelle, l'UI de test avance explicitement :
            // le curseur qui bouge pendant la lecture ne doit pas bloquer Espresso.
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { projects.togglePlay(id) }
            compose.waitUntil(5000) { projects.uiState.value.positionMs > 150 }
            var previous = 0L
            compose.runOnIdle { previous = projects.uiState.value.positionMs }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithContentDescription("Tempo du projet en BPM").performSemanticsAction(SemanticsActions.SetProgress) { it(180f) }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {
                assertEquals(id, projects.uiState.value.playingProjectId)
                assertEquals(180, projects.project(id)!!.bpm)
                // La même position musicale a deux fois moins de millisecondes.
                assertTrue(projects.uiState.value.positionMs >= previous / 2 - 80)
                assertNull(projects.uiState.value.errorMessage)
                projects.pause()
                val reloaded = ProjectsViewModel()
                reloaded.attach(directory, directory)
                assertEquals(projects.project(id), reloaded.project(id))
                assertEquals(180, reloaded.uiState.value.lastBpm)
            }
        } finally {
            compose.runOnIdle { projects.stop() }
        }
    }
}
