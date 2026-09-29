package com.example.voicetomidi.pipeline

import com.example.voicetomidi.audio.DemoScenario
import com.example.voicetomidi.audio.SyntheticVoiceSource
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.notes.RawPitchFrame
import com.example.voicetomidi.notes.ViterbiNoteSegmenter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Teste le pipeline complet (fenêtrage réel, YIN réel, NoteSegmenter temps
 * réel, puis ViterbiNoteSegmenter) de bout en bout, en rejouant chaque
 * [DemoScenario] à la place du micro — exactement le mécanisme du mode démo
 * de l'UI, automatisé pour attraper les régressions sans avoir à chanter ni
 * ouvrir l'app.
 */
class VoiceToMidiPipelineIntegrationTest {

    private fun runScenario(scenario: DemoScenario): List<NoteEvent> = runBlocking {
        val source = SyntheticVoiceSource(scenario = scenario, speedMultiplier = 50f)
        val pipeline = VoiceToMidiPipeline(audioSource = source)

        val liveFrames = pipeline.run().toList()
        val rawFrames = liveFrames.map {
            RawPitchFrame(it.timestampMs, it.pitch.frequencyHz, it.pitch.confidence, it.rmsAmplitude)
        }
        ViterbiNoteSegmenter().segment(rawFrames)
    }

    @Test
    fun `standard scenario is recovered as four clean notes`() {
        val events = runScenario(DemoScenario.STANDARD)
        assertEquals(listOf(60, 64, 67, 72), events.map { it.midiNote })
    }

    @Test
    fun `imprecise singer scenario does not fragment into spurious notes`() {
        val events = runScenario(DemoScenario.IMPRECISE_SINGER)
        assertEquals(listOf(69, 64), events.map { it.midiNote })
    }

    @Test
    fun `fast arpeggio scenario recovers every note`() {
        val events = runScenario(DemoScenario.FAST_ARPEGGIO)
        assertEquals(listOf(60, 64, 67, 72, 67, 64, 60), events.map { it.midiNote })
    }

    @Test
    fun `repeated note scenario separates each repetition`() {
        val events = runScenario(DemoScenario.REPEATED_NOTE)
        assertEquals(listOf(65, 65, 65), events.map { it.midiNote })
    }

    @Test
    fun `abrupt transition scenario does not leave a spurious short note`() {
        val events = runScenario(DemoScenario.ABRUPT_TRANSITION)
        assertEquals(listOf(64, 67, 72), events.map { it.midiNote })
    }
}
