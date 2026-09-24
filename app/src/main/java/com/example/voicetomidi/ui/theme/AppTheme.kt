package com.example.voicetomidi.ui.theme

/** Thèmes visuels proposés à l'utilisateur (choix au premier lancement, modifiable ensuite). */
enum class AppTheme(val displayName: String, val tagline: String) {
    Studio(displayName = "Studio", tagline = "Indigo sobre, look console de mixage"),
    Vinyl(displayName = "Vinyle", tagline = "Tons chauds, ambiance analogique rétro"),
    Synthwave(displayName = "Synthwave", tagline = "Néons vibrants, esprit synthé électro")
}
