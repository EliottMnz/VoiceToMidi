package com.example.voicetomidi.ui

import com.example.voicetomidi.audio.TempoPlayer
import com.example.voicetomidi.project.DEFAULT_PROJECT_BPM
import com.example.voicetomidi.project.MIN_PROJECT_BPM
import com.example.voicetomidi.project.MAX_PROJECT_BPM
import com.example.voicetomidi.project.scaleTime
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val positionMs: Long = 0L,
    val lastBpm: Int = DEFAULT_PROJECT_BPM,
    val errorMessage: String? = null
)

private const val PROJECTS_FILE_NAME = "projets.json"
private const val PLAYBACK_REFRESH_MS = 33L

/**
 * La composition : des projets, chacun avec ses pistes (mélodies, plus tard
 * drums). Les pistes partagent une seule horloge audio ; le tempo peut changer
 * pendant la lecture sans redémarrer le morceau.
 *
 * Partagé par toute l'appli (un seul ViewModel pour l'activité) ; les projets
 * sont sauvegardés sur le téléphone à chaque changement.
 */
class ProjectsViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectsUiState())
    val uiState: StateFlow<ProjectsUiState> = _uiState.asStateFlow()

    private var storeFile: File? = null
    private var player: TempoPlayer? = null
    private var positionProjectId: Long? = null
    private var playbackJob: Job? = null

    /** Branche le stockage (une seule fois) et recharge les projets sauvegardés. */
    fun attach(filesDir: File, cacheDir: File) {
        if (storeFile != null) return
        val file = File(filesDir, PROJECTS_FILE_NAME)
        storeFile = file
        val saved = try {
            if (file.exists()) ProjectJson.decode(file.readText()) else emptyList()
        } catch (e: Exception) {
            // Fichier abîmé : on le garde de côté plutôt que de l'écraser.
            file.renameTo(File(filesDir, "$PROJECTS_FILE_NAME.illisible"))
            emptyList()
        }
        val lastBpm = try {
            if (file.exists()) org.json.JSONObject(file.readText()).optInt("lastBpm", DEFAULT_PROJECT_BPM)
                .coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM) else DEFAULT_PROJECT_BPM
        } catch (_: Exception) { DEFAULT_PROJECT_BPM }
        _uiState.update { it.copy(projects = saved, lastBpm = lastBpm) }
    }

    fun project(id: Long): Project? = _uiState.value.projects.firstOrNull { it.id == id }

    /** Nom proposé pour un nouveau projet ("Projet 1", "Projet 2"...). */
    fun suggestedProjectName(): String {
        val used = _uiState.value.projects.map { it.name }.toSet()
        return generateSequence(_uiState.value.projects.size + 1) { it + 1 }.map { "Projet $it" }.first { it !in used }
    }

    /** Crée un projet vide et renvoie son id. */
    fun createProject(name: String, bpm: Int = _uiState.value.lastBpm): Long {
        val id = newId()
        _uiState.update { it.copy(lastBpm = bpm.coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM)) }
        updateProjects { it + Project(id = id, name = name.trim().ifEmpty { suggestedProjectName() }, bpm = bpm.coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM)) }
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
    fun addMelody(projectId: Long, notes: List<NoteEvent>, sourceBpm: Int? = null): String {
        val name = project(projectId)?.nextMelodyName() ?: "Mélodie"
        val track = ProjectTrack(id = newId(), name = name, kind = TrackKind.MELODY, notes = notes,
            sourceBpm = (sourceBpm ?: project(projectId)?.bpm ?: DEFAULT_PROJECT_BPM).coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM))
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
        val from = if (positionProjectId == projectId) _uiState.value.positionMs else 0L
        stop()
        play(projectId, from)
    }

    /** Place la lecture à [positionMs] (toucher la ligne de temps) ; si le projet joue, il repart de là. */
    fun seek(projectId: Long, positionMs: Long) {
        val playing = _uiState.value.playingProjectId == projectId
        if (playing) stopPlayer()
        positionProjectId = projectId
        _uiState.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
        if (playing) play(projectId, positionMs)
    }

    /** En changeant de projet, la lecture repart du début. */
    fun resetPosition() {
        stop()
        _uiState.update { it.copy(positionMs = 0L) }
    }

    /** Mémorise le dernier tempo choisi hors projet pour la prochaine prise. */
    fun rememberTempo(bpm: Int) {
        val chosen = bpm.coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM)
        if (_uiState.value.lastBpm == chosen) return
        _uiState.update { it.copy(lastBpm = chosen) }
        save()
    }

    /** Le tempo appartient au projet ; la position est conservée en temps musicaux. */
    fun setTempo(projectId: Long, bpm: Int) {
        val project = project(projectId) ?: return
        val chosen = bpm.coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM)
        if (chosen == project.bpm) { rememberTempo(chosen); return }
        val sourcePosition = player?.positionMs()
        val position = if (positionProjectId == projectId) {
            sourcePosition?.let { scaleTime(it.toLong(), DEFAULT_PROJECT_BPM, chosen) }
                ?: scaleTime(_uiState.value.positionMs, project.bpm, chosen)
        } else _uiState.value.positionMs
        player?.takeIf { _uiState.value.playingProjectId == projectId }?.setTempo(chosen)
        _uiState.update { it.copy(lastBpm = chosen, positionMs = position) }
        updateProject(projectId) { it.copy(bpm = chosen) }
    }

    /** Cale toutes les pistes sur les doubles-croches du projet, sans changer leur hauteur. */
    fun alignToTempo(projectId: Long) {
        updateProject(projectId) { project -> project.copy(tracks = project.tracks.map { it.alignedToGrid() }) }
        restartIfPlaying(projectId)
    }

    private fun play(projectId: Long, fromMs: Long) {
        val project = project(projectId) ?: return
        val audible = project.tracks.filterNot { it.muted }
        if (audible.isEmpty() || project.durationMs == 0L) return
        val start = if (fromMs >= project.durationMs) 0L else fromMs
        val audio = try {
            TempoPlayer(audible.flatMap { it.notesAtTempo(DEFAULT_PROJECT_BPM) },
                start.toDouble() * project.bpm / DEFAULT_PROJECT_BPM, project.bpm)
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Écoute impossible : ${e.message}") }
            return
        }
        positionProjectId = projectId
        player = audio
        _uiState.update { it.copy(playingProjectId = projectId, positionMs = start, errorMessage = null) }
        audio.start()
        playbackJob = viewModelScope.launch {
            while (true) {
                if (audio.completed || audio.failure != null) {
                    val failure = audio.failure
                    stop()
                    _uiState.update { it.copy(positionMs = if (failure == null) 0L else it.positionMs,
                        errorMessage = failure?.let { message -> "Écoute impossible : $message" }) }
                    break
                }
                val currentBpm = project(projectId)?.bpm ?: project.bpm
                _uiState.update { it.copy(positionMs = scaleTime(audio.positionMs().toLong(), DEFAULT_PROJECT_BPM, currentBpm)) }
                delay(PLAYBACK_REFRESH_MS)
            }
        }
    }

    private fun restartIfPlaying(projectId: Long) {
        if (_uiState.value.playingProjectId != projectId) return
        capturePosition()
        val position = _uiState.value.positionMs
        stopPlayer()
        play(projectId, position)
    }

    /** Pause : la position est gardée pour la prochaine lecture. */
    fun pause() {
        capturePosition()
        stopPlayer()
    }

    fun stop() {
        capturePosition()
        stopPlayer()
    }

    private fun capturePosition() {
        val audio = player ?: return
        val bpm = positionProjectId?.let { project(it)?.bpm } ?: return
        _uiState.update { it.copy(positionMs = scaleTime(audio.positionMs().toLong(), DEFAULT_PROJECT_BPM, bpm)) }
    }

    private fun stopPlayer() {
        playbackJob?.cancel()
        playbackJob = null
        player?.stop()
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
            temp.writeText(org.json.JSONObject(ProjectJson.encode(_uiState.value.projects))
                .put("lastBpm", _uiState.value.lastBpm).toString())
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
