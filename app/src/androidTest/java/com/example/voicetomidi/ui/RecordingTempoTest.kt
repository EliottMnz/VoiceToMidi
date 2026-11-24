package com.example.voicetomidi.ui

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Régression : le tempo source d'une prise ne doit pas être remplacé par le tempo d'écoute. */
class RecordingTempoTest {
    @Test fun unePriseChangeDeVitesseEtSeCaleSansChangerLesHauteurs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recording = RecordingViewModel()
        instrumentation.runOnMainSync { recording.setProjectBpm(120); recording.startDemo() }
        try {
            val deadline = System.currentTimeMillis() + 15000
            while (recording.uiState.value.isRecording || recording.uiState.value.isRefining) {
                check(System.currentTimeMillis() < deadline) { "Analyse de démo trop longue" }
                Thread.sleep(20)
            }
            instrumentation.runOnMainSync {
                val original = recording.melodyForComposition()
                assertTrue(original.isNotEmpty())
                recording.setProjectBpm(240)
                val fast = recording.melodyForComposition()
                assertEquals(original.map { it.midiNote }, fast.map { it.midiNote })
                original.zip(fast).forEach { (slow, quick) ->
                    assertEquals(slow.startTimeMs / 2.0, quick.startTimeMs.toDouble(), 1.0)
                    assertEquals(slow.endTimeMs / 2.0, quick.endTimeMs.toDouble(), 1.0)
                }
                recording.setProjectBpm(120)
                assertEquals(original, recording.melodyForComposition())
                recording.alignToTempo()
                val aligned = recording.melodyForComposition()
                assertEquals(original.map { it.midiNote }, aligned.map { it.midiNote })
                assertEquals(aligned, recording.notesForDisplay())
                aligned.forEach { note ->
                    assertEquals(0L, note.startTimeMs % 125L)
                    assertTrue(note.endTimeMs > note.startTimeMs)
                }
                assertTrue(recording.buildMidiFile().size > 20)
            }
        } finally {
            instrumentation.runOnMainSync { recording.stopRecording(); recording.stopPreview() }
        }
    }
}
