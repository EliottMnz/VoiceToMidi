package com.example.voicetomidi.ui

import android.content.Context
import androidx.core.content.edit
import com.example.voicetomidi.ui.theme.AppTheme

private const val PREFS_NAME = "voicetomidi_prefs"
private const val KEY_SELECTED_THEME = "selected_theme"

/** Persiste le thème choisi par l'utilisateur (SharedPreferences, aucune dépendance ajoutée). */
object ThemePreferences {

    fun load(context: Context): AppTheme? {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_THEME, null)
        return saved?.let { name -> AppTheme.entries.firstOrNull { it.name == name } }
    }

    fun save(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_SELECTED_THEME, theme.name)
        }
    }
}
