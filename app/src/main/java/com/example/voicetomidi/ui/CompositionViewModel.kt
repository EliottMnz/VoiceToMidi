package com.example.voicetomidi.ui

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.NoteEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** D'où vient une piste de la composition. Les drums arriveront plus tard. */
enum class TrackKind { MELODY, DRUMS }

/** Une piste envoyée dans la composition (depuis le mode Mélodie, plus tard depuis les Drums). */
data class CompositionTrack(
    val id: Long,
    val name: String,
    val kind: TrackKind,
    /** Notes à partir de 0 ms, telles qu'elles seraient exportées. */
    val notes: List<NoteEvent>
) {
    val durationMs: Long get() = notes.maxOfOrNull { it.endTimeMs } ?: 0L
}

data class CompositionUiState(
    val tracks: List<CompositionTrack> = emptyList(),
    /** Piste en cours d'écoute, ou null. */
    val playingTrackId: Long? = null
)

/**
 * La composition : les pistes envoyées par les différents modes. Pour
 * l'instant elle les rassemble et les fait écouter ; assembler un morceau
 * complet (caler les pistes, exporter le tout) viendra ensuite.
 *
 * Partagée par toute l'appli (un seul ViewModel pour l'activité). Les pistes
 * restent en mémoire : elles sont perdues si Android ferme l'appli.
 */
class CompositionViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CompositionUiState())
    val uiState: StateFlow<CompositionUiState> = _uiState.asStateFlow()

    /** Dossier temporaire où écrire le MIDI à écouter (le synthé d'Android lit un fichier). */
    var previewDir: File? = null

    private var nextId = 1L
    private var melodyCount = 0
    private var player: MediaPlayer? = null

    /** Ajoute une mélodie et renvoie le nom qui lui est donné ("Mélodie 1", "Mélodie 2"...). */
    fun addMelody(notes: List<NoteEvent>): String {
        melodyCount++
        val name = "Mélodie $melodyCount"
        val track = CompositionTrack(id = nextId++, name = name, kind = TrackKind.MELODY, notes = notes)
        _uiState.update { it.copy(tracks = it.tracks + track) }
        return name
    }

    fun removeTrack(id: Long) {
        if (_uiState.value.playingTrackId == id) stop()
        _uiState.update { state -> state.copy(tracks = state.tracks.filterNot { it.id == id }) }
    }

    /** Écoute une piste au piano ; un 2e appui sur la même piste l'arrête. */
    fun togglePlay(track: CompositionTrack) {
        val wasPlaying = _uiState.value.playingTrackId == track.id
        stop()
        if (wasPlaying) return
        val dir = previewDir ?: return
        player = try {
            val file = File(dir, "piste_${track.id}.mid").apply { writeBytes(MidiFileWriter().write(track.notes)) }
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { stop() }
                start()
            }
        } catch (e: Exception) {
            return
        }
        _uiState.update { it.copy(playingTrackId = track.id) }
    }

    fun stop() {
        player?.release()
        player = null
        _uiState.update { it.copy(playingTrackId = null) }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
