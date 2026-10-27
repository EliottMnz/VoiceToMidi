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
import com.example.voicetomidi.ui.CompositionScreen
import com.example.voicetomidi.ui.CompositionViewModel
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
                    // Partagée par tous les modes : chacun peut y envoyer ses pistes.
                    val composition: CompositionViewModel = viewModel()
                    composition.previewDir = cacheDir
                    val compositionState by composition.uiState.collectAsState()
                    val goHome = { destination = Destination.HOME }

                    BackHandler(enabled = destination != Destination.HOME, onBack = goHome)

                    // Comme sur iPhone : un mode arrive par la droite, le retour à l'accueil repart vers la droite.
                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = {
                            if (targetState == Destination.HOME) {
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
                                trackCount = compositionState.tracks.size,
                                onOpen = { destination = it }
                            )
                            Destination.MELODY -> RecordingScreen(
                                onBack = goHome,
                                onSendToComposition = composition::addMelody
                            )
                            Destination.COMPOSITION -> CompositionScreen(
                                viewModel = composition,
                                onBack = goHome,
                                onCreateMelody = { destination = Destination.MELODY }
                            )
                            Destination.DRUMS -> DrumsScreen(onBack = goHome)
                        }
                    }
                }
            }
        }
    }
}
