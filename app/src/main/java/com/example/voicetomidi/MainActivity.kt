package com.example.voicetomidi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.voicetomidi.ui.RecordingScreen
import com.example.voicetomidi.ui.ThemePickerScreen
import com.example.voicetomidi.ui.ThemePreferences
import com.example.voicetomidi.ui.theme.AppTheme
import com.example.voicetomidi.ui.theme.VoiceToMidiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var selectedTheme by remember { mutableStateOf(ThemePreferences.load(context)) }
            var showThemePicker by remember { mutableStateOf(selectedTheme == null) }

            VoiceToMidiTheme(appTheme = selectedTheme ?: AppTheme.Studio) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showThemePicker) {
                        ThemePickerScreen(
                            modifier = Modifier.padding(innerPadding),
                            onThemeChosen = { theme ->
                                selectedTheme = theme
                                ThemePreferences.save(context, theme)
                                showThemePicker = false
                            }
                        )
                    } else {
                        RecordingScreen(
                            modifier = Modifier.padding(innerPadding),
                            onChangeTheme = { showThemePicker = true }
                        )
                    }
                }
            }
        }
    }
}
