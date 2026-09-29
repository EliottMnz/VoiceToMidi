package com.example.voicetomidi.audio

/** Un événement de mélodie de démo : une note tenue, ou un silence si [midiNote] est null. */
data class DemoNote(val midiNote: Int?, val durationMs: Long)

/**
 * Quelques scénarios de test couvrant des cas différents du pipeline, pour
 * vérifier que tout marche (et repérer les régressions) sans avoir besoin de
 * chanter. [driftCents] contrôle l'amplitude du vibrato/bruit simulé.
 */
enum class DemoScenario(val displayName: String, val notes: List<DemoNote>, val driftCents: Double = 20.0) {
    STANDARD(
        displayName = "Standard (tenues + vibrato)",
        notes = listOf(
            DemoNote(60, 800), DemoNote(null, 300),
            DemoNote(64, 500), DemoNote(67, 500),
            DemoNote(null, 300), DemoNote(72, 900)
        )
    ),
    IMPRECISE_SINGER(
        displayName = "Chanteur imprécis (forte dérive)",
        notes = listOf(
            DemoNote(69, 1200), DemoNote(null, 300), DemoNote(64, 900)
        ),
        driftCents = 100.0
    ),
    FAST_ARPEGGIO(
        displayName = "Arpège rapide (grands sauts, notes courtes)",
        notes = listOf(
            DemoNote(60, 150), DemoNote(64, 150), DemoNote(67, 150), DemoNote(72, 150),
            DemoNote(67, 150), DemoNote(64, 150), DemoNote(60, 300)
        )
    ),
    REPEATED_NOTE(
        displayName = "Notes répétées (même hauteur, silences nets)",
        notes = listOf(
            DemoNote(65, 400), DemoNote(null, 200),
            DemoNote(65, 400), DemoNote(null, 200),
            DemoNote(65, 600)
        )
    ),
    ABRUPT_TRANSITION(
        displayName = "Transitions brutales (régression note parasite)",
        notes = listOf(
            DemoNote(64, 600), DemoNote(67, 600), DemoNote(72, 600)
        )
    )
}
