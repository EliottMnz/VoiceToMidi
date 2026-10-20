package com.example.voicetomidi.audio

/**
 * Un événement de mélodie de démo : une note tenue, ou un silence si
 * [midiNote] est null. [detuneCents] la fait chanter un peu à côté.
 */
data class DemoNote(val midiNote: Int?, val durationMs: Long, val detuneCents: Double = 0.0)

/**
 * Quelques scénarios de test couvrant des cas différents du pipeline, pour
 * vérifier que tout marche (et repérer les régressions) sans avoir besoin de
 * chanter. [driftCents] contrôle l'amplitude du vibrato/bruit simulé.
 */
enum class DemoScenario(
    val displayName: String,
    val notes: List<DemoNote>,
    val driftCents: Double = 20.0,
    /** Décalage de toute la voix (un chanteur qui chante tout un peu trop bas, par exemple). */
    val globalOffsetCents: Double = 0.0
) {
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
    ),
    AU_CLAIR_DE_LA_LUNE(
        displayName = "Au clair de la lune (débutant, ~30 s)",
        notes = auClairDeLaLune(),
        driftCents = 25.0,
        globalOffsetCents = -30.0
    )
}

/**
 * "Au clair de la lune" en Do majeur à 120 BPM (noire = 500 ms), chantée par
 * un débutant : toute la voix 30 cents trop basse ([DemoScenario.globalOffsetCents]),
 * des notes un peu fausses, de brèves hésitations vers la note voisine au
 * milieu des notes longues ("doooo do# doo"), et une courte respiration entre
 * les notes (consonnes).
 * Mélodie attendue : voir [AU_CLAIR_DE_LA_LUNE_MELODY].
 */
private fun auClairDeLaLune(): List<DemoNote> {
    val beatMs = 500L
    val gapMs = 60L
    val notes = mutableListOf<DemoNote>()
    fun sing(midi: Int, beats: Int, detune: Double = 0.0) {
        notes += DemoNote(midi, beats * beatMs - gapMs, detune)
        notes += DemoNote(null, gapMs)
    }
    fun singWithHesitation(midi: Int, beats: Int, neighbour: Int) {
        val total = beats * beatMs - gapMs
        notes += DemoNote(midi, total / 2)
        notes += DemoNote(neighbour, 70)
        notes += DemoNote(midi, total - total / 2 - 70)
        notes += DemoNote(null, gapMs)
    }
    fun phraseA(hesitate: Boolean) {
        sing(60, 1); sing(60, 1, 15.0); sing(60, 1); sing(62, 1, -20.0)
        if (hesitate) singWithHesitation(64, 2, 65) else sing(64, 2); sing(62, 2, 20.0)
        sing(60, 1); sing(64, 1, -15.0); sing(62, 1); sing(62, 1, 25.0)
        if (hesitate) singWithHesitation(60, 4, 61) else sing(60, 4)
    }
    fun phraseB() {
        sing(62, 1); sing(62, 1, -20.0); sing(62, 1); sing(62, 1, 10.0)
        sing(57, 2, 20.0); sing(57, 2)
        sing(62, 1); sing(60, 1, -25.0); sing(59, 1); sing(57, 1, 15.0)
        singWithHesitation(55, 4, 56)
    }
    phraseA(hesitate = false)
    phraseB()
    phraseA(hesitate = true)
    notes += DemoNote(null, 400)
    return notes
}

/** Les notes que l'analyse doit retrouver pour [DemoScenario.AU_CLAIR_DE_LA_LUNE]. */
val AU_CLAIR_DE_LA_LUNE_MELODY: List<Int> = run {
    val a = listOf(60, 60, 60, 62, 64, 62, 60, 64, 62, 62, 60)
    val b = listOf(62, 62, 62, 62, 57, 57, 62, 60, 59, 57, 55)
    a + b + a
}
