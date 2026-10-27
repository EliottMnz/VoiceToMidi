package com.example.voicetomidi

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.voicetomidi.ui.ProjectScreen
import com.example.voicetomidi.ui.ProjectsScreen
import com.example.voicetomidi.ui.ProjectsViewModel
import com.example.voicetomidi.ui.Destination
import com.example.voicetomidi.ui.DrumsScreen
import com.example.voicetomidi.ui.HomeScreen
import com.example.voicetomidi.ui.RecordingScreen
import com.example.voicetomidi.ui.theme.VoiceToMidiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // L'appli est toujours sombre : icônes claires sur les barres système, même si le téléphone est en thème clair.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            VoiceToMidiTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
                    // Projet ouvert, et projet qui a lancé le mode Mélodie (« Ajouter une mélodie »).
                    var openProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
                    var melodyTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
                    // Partagée par tous les modes : chacun peut y envoyer ses pistes.
                    val projects: ProjectsViewModel = viewModel()
                    projects.attach(filesDir, cacheDir)
                    val projectsState by projects.uiState.collectAsState()
                    val goHome = { destination = Destination.HOME }
                    val openProject = { id: Long ->
                        openProjectId = id
                        destination = Destination.PROJECT
                    }
                    val back = {
                        when (destination) {
                            Destination.PROJECT -> destination = Destination.COMPOSITION
                            Destination.MELODY -> melodyTargetId?.let(openProject) ?: goHome()
                            else -> goHome()
                        }
                    }
                    // Profondeur de chaque écran : on avance vers la droite, on revient vers la gauche.
                    fun depth(screen: Destination) = when (screen) {
                        Destination.HOME -> 0
                        Destination.COMPOSITION, Destination.DRUMS -> 1
                        Destination.PROJECT -> 2
                        Destination.MELODY -> if (melodyTargetId != null) 3 else 1
                    }

                    BackHandler(enabled = destination != Destination.HOME, onBack = back)

                    // Comme sur iPhone : un écran plus profond arrive par la droite, le retour repart vers la droite.
                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = {
                            if (depth(targetState) < depth(initialState)) {
                                (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                            } else {
                                (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                            }
                        },
                        label = "navigation",
                        modifier = Modifier.padding(innerPadding)
                    ) { screen ->
                        when (screen) {
                            Destination.HOME -> HomeScreen(
                                projectCount = projectsState.projects.size,
                                onOpen = {
                                    melodyTargetId = null
                                    destination = it
                                }
                            )
                            Destination.MELODY -> RecordingScreen(
                                projects = projects,
                                targetProjectId = melodyTargetId,
                                onBack = back,
                                onSentToTarget = back
                            )
                            Destination.COMPOSITION -> ProjectsScreen(
                                viewModel = projects,
                                onBack = goHome,
                                onOpenProject = openProject
                            )
                            Destination.PROJECT -> ProjectScreen(
                                projectId = openProjectId ?: -1L,
                                viewModel = projects,
                                onBack = back,
                                onAddMelody = {
                                    melodyTargetId = openProjectId
                                    destination = Destination.MELODY
                                }
                            )
                            Destination.DRUMS -> DrumsScreen(onBack = goHome)
                        }
                    }
                }
            }
        }
    }
}
