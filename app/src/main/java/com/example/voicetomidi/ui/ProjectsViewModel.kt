package com.example.voicetomidi.ui

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.voicetomidi.midi.MidiFileWriter
import com.example.voicetomidi.notes.NoteEvent
import com.example.voicetomidi.project.Project
import com.example.voicetomidi.project.ProjectJson
import com.example.voicetomidi.project.ProjectTrack
import com.example.voicetomidi.project.TrackKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ProjectsUiState(
    val projects: List<Project> = emptyList(),
    /** Projet en cours d'écoute, ou null. */
    val playingProjectId: Long? = null,
    /** Position de lecture dans le projet écouté (ms), gardée en pause. */
    val positionMs: Long = 0L
)

private const val PROJECTS_FILE_NAME = "projets.json"
private const val PLAYBACK_REFRESH_MS = 33L

/**
 * La composition : des projets, chacun avec ses pistes (mélodies, plus tard
 * drums). Toutes les pistes non coupées d'un projet sont écrites dans un seul
 * fichier MIDI, chacune sur son canal : elles jouent donc ensemble, synchro.
 *
 * Partagé par toute l'appli (un seul ViewModel pour l'activité) ; les projets
 * sont sauvegardés sur le téléphone à chaque changement.
 */
class ProjectsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    private var storeFile: File? = null
    private var previewDir: File? = null
    private var player: MediaPlayer? = null
    private var playbackJob: Job? = null

    /** Branche le stockage (une seule fois) et recharge les projets sauvegardés. */
    fun attach(filesDir: File, cacheDir: File) {
        if (storeFile != null) return
        previewDir = cacheDir
        val file = File(filesDir, PROJECTS_FILE_NAME)
        storeFile = file
        val saved = try {
            if (file.exists()) ProjectJson.decode(file.readText()) else emptyList()
        } catch (e: Exception) {
            // Fichier abîmé : on le garde de côté plutôt que de l'écraser.
            file.renameTo(File(filesDir, "$PROJECTS_FILE_NAME.illisible"))
            emptyList()
        }
        _uiState.update { it.copy(projects = saved) }
    }

    fun project(id: Long): Project? = _uiState.value.projects.firstOrNull { it.id == id }

    /** Nom proposé pour un nouveau projet ("Projet 1", "Projet 2"...). */
    fun suggestedProjectName(): String {
        val used = _uiState.value.projects.map { it.name }.toSet()
        return generateSequence(_uiState.value.projects.size + 1) { it + 1 }.map { "Projet $it" }.first { it !in used }
    }

    /** Crée un projet vide et renvoie son id. */
    fun createProject(name: String): Long {
        val id = newId()
        updateProjects { it + Project(id = id, name = name.trim().ifEmpty { suggestedProjectName() }) }
        return id
    }

    fun renameProject(id: Long, name: String) {
        if (name.isBlank()) return
        updateProject(id) { it.copy(name = name.trim()) }
    }

    fun deleteProject(id: Long) {
        if (_uiState.value.playingProjectId == id) stop()
        updateProjects { projects -> projects.filterNot { it.id == id } }
    }

    /** Ajoute une mélodie au projet et renvoie le nom donné à la piste. */
    fun addMelody(projectId: Long, notes: List<NoteEvent>): String {
        val name = project(projectId)?.nextMelodyName() ?: "Mélodie"
        val track = ProjectTrack(id = newId(), name = name, kind = TrackKind.MELODY, notes = notes)
        updateProject(projectId) { it.copy(tracks = it.tracks + track) }
        restartIfPlaying(projectId)
        return name
    }

    fun toggleMute(projectId: Long, trackId: Long) {
        updateProject(projectId) { project ->
            project.copy(tracks = project.tracks.map { if (it.id == trackId) it.copy(muted = !it.muted) else it })
        }
        // En pleine écoute, on entend tout de suite la différence, sans perdre la position.
        restartIfPlaying(projectId)
    }

    fun removeTrack(projectId: Long, trackId: Long) {
        updateProject(projectId) { project -> project.copy(tracks = project.tracks.filterNot { it.id == trackId }) }
        restartIfPlaying(projectId)
    }

    /** Lecture / pause du projet : toutes les pistes non coupées, ensemble. */
    fun togglePlay(projectId: Long) {
        if (_uiState.value.playingProjectId == projectId) {
            pause()
            return
        }
        val from = if (_uiState.value.playingProjectId == null) _uiState.value.positionMs else 0L
        stop()
        play(projectId, from)
    }

    /** Place la lecture à [positionMs] (toucher la ligne de temps) ; si le projet joue, il repart de là. */
    fun seek(projectId: Long, positionMs: Long) {
        val playing = _uiState.value.playingProjectId == projectId
        if (playing) stopPlayer()
        _uiState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
        if (playing) play(projectId, positionMs)
    }

    /** En changeant de projet, la lecture repart du début. */
    fun resetPosition() {
        stop()
        _uiState.update { it.copy(positionMs = 0L) }
    }

    private fun play(projectId: Long, fromMs: Long) {
        val project = project(projectId) ?: return
        val audible = project.tracks.filterNot { it.muted }
        val dir = previewDir ?: return
        if (audible.isEmpty() || project.durationMs == 0L) return
        val start = if (fromMs >= project.durationMs) 0L else fromMs
        val mediaPlayer = try {
            val file = File(dir, "projet_$projectId.mid").apply { writeBytes(MidiFileWriter().writeTogether(audible.map { it.notes })) }
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                seekTo(start.toInt())
                setOnCompletionListener {
                    stop()
                    _uiState.update { state -> state.copy(positionMs = 0L) }
                }
                start()
            }
        } catch (e: Exception) {
            return
        }
        player = mediaPlayer
        _uiState.update { it.copy(playingProjectId = projectId, positionMs = start) }
        playbackJob = viewModelScope.launch {
            while (true) {
                _uiState.update { it.copy(positionMs = mediaPlayer.currentPosition.toLong()) }
                delay(PLAYBACK_REFRESH_MS)
            }
        }
    }

    private fun restartIfPlaying(projectId: Long) {
        if (_uiState.value.playingProjectId != projectId) return
        val position = _uiState.value.positionMs
        stopPlayer()
        play(projectId, position)
    }

    /** Pause : la position est gardée pour la prochaine lecture. */
    fun pause() {
        stopPlayer()
    }

    fun stop() {
        stopPlayer()
    }

    private fun stopPlayer() {
        playbackJob?.cancel()
        playbackJob = null
        player?.release()
        player = null
        _uiState.update { it.copy(playingProjectId = null) }
    }

    private fun updateProject(id: Long, change: (Project) -> Project) =
        updateProjects { projects -> projects.map { if (it.id == id) change(it) else it } }

    private fun updateProjects(change: (List<Project>) -> List<Project>) {
        _uiState.update { it.copy(projects = change(it.projects)) }
        save()
    }

    private fun save() {
        val file = storeFile ?: return
        try {
            // Écriture dans un fichier à côté puis renommage : jamais de fichier à moitié écrit.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ProjectJson.encode(_uiState.value.projects))
            temp.renameTo(file)
        } catch (e: Exception) {
            // Sauvegarde ratée (disque plein...) : les projets restent en mémoire.
        }
    }

    // Ids uniques sur tous les projets et pistes, y compris ceux rechargés.
    private fun newId(): Long {
        val maxUsed = _uiState.value.projects.flatMap { p -> listOf(p.id) + p.tracks.map { it.id } }.maxOrNull() ?: 0L
        return maxUsed + 1
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
