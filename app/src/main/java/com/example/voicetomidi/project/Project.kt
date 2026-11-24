package com.example.voicetomidi.project

import com.example.voicetomidi.notes.NoteEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong
import com.example.voicetomidi.notes.quantize

/** D'où vient une piste. Les drums arriveront plus tard. */
enum class TrackKind { MELODY, DRUMS }

/** Une piste d'un projet : une mélodie (plus tard, des drums). */
data class ProjectTrack(
    val id: Long,
    val name: String,
    val kind: TrackKind,
    /** Notes à partir de 0 ms : toutes les pistes d'un projet démarrent ensemble. */
    val notes: List<NoteEvent>,
    /** Piste coupée : on la voit toujours, mais elle ne joue pas. */
    val muted: Boolean = false,
    /** Tempo auquel les instants de notes ont été enregistrés. */
    val sourceBpm: Int = DEFAULT_PROJECT_BPM
) {
    val durationMs: Long get() = notes.maxOfOrNull { it.endTimeMs } ?: 0L

    /** Change la durée musicale sans toucher aux notes sources, même après plusieurs changements de BPM. */
    fun notesAtTempo(bpm: Int): List<NoteEvent> = notes.map { note ->
        val start = scaleTime(note.startTimeMs, sourceBpm, bpm)
        note.copy(startTimeMs = start, endTimeMs = scaleTime(note.endTimeMs, sourceBpm, bpm).coerceAtLeast(start + 1))
    }

    /** La grille se calcule dans le tempo source : le résultat reste calé à tous les tempos du projet. */
    fun alignedToGrid(): ProjectTrack = copy(notes = quantize(notes, sourceBpm))
}

/** Un projet de la composition : des pistes qui jouent ensemble. */
data class Project(
    val id: Long,
    val name: String,
    val tracks: List<ProjectTrack> = emptyList(),
    val bpm: Int = DEFAULT_PROJECT_BPM
) {
    val durationMs: Long get() = tracks.maxOfOrNull { scaleTime(it.durationMs, it.sourceBpm, bpm) } ?: 0L

    /** Nom de la prochaine mélodie : "Mélodie 1", "Mélodie 2"... sans doublon même après un retrait. */
    fun nextMelodyName(): String {
        val used = tracks.map { it.name }.toSet()
        return generateSequence(tracks.count { it.kind == TrackKind.MELODY } + 1) { it + 1 }
            .map { "Mélodie $it" }
            .first { it !in used }
    }
}

/**
 * Lecture/écriture des projets en JSON (un seul fichier, petit : quelques
 * notes par piste). Une version est écrite pour pouvoir faire évoluer le
 * format sans perdre les projets existants.
 */
object ProjectJson {
    private const val VERSION = 1

    fun encode(projects: List<Project>): String = JSONObject()
        .put("version", VERSION)
        .put("projects", JSONArray(projects.map { project ->
            JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("bpm", project.bpm)
                .put("tracks", JSONArray(project.tracks.map { track ->
                    JSONObject()
                        .put("id", track.id)
                        .put("name", track.name)
                        .put("kind", track.kind.name)
                        .put("muted", track.muted)
                        .put("sourceBpm", track.sourceBpm)
                        // Une note = [hauteur, début, fin, vélocité] : compact et lisible.
                        .put("notes", JSONArray(track.notes.map { note ->
                            JSONArray(listOf(note.midiNote, note.startTimeMs, note.endTimeMs, note.velocity))
                        }))
                }))
        }))
        .toString()

    fun decode(json: String): List<Project> {
        val projects = JSONObject(json).optJSONArray("projects") ?: return emptyList()
        return (0 until projects.length()).map { i ->
            val project = projects.getJSONObject(i)
            val tracks = project.getJSONArray("tracks")
            Project(
                id = project.getLong("id"),
                name = project.getString("name"),
                bpm = project.optInt("bpm", DEFAULT_PROJECT_BPM).coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM),
                tracks = (0 until tracks.length()).map { j ->
                    val track = tracks.getJSONObject(j)
                    val notes = track.getJSONArray("notes")
                    ProjectTrack(
                        id = track.getLong("id"),
                        name = track.getString("name"),
                        kind = TrackKind.valueOf(track.getString("kind")),
                        muted = track.optBoolean("muted", false),
                        sourceBpm = track.optInt("sourceBpm", DEFAULT_PROJECT_BPM).coerceIn(MIN_PROJECT_BPM, MAX_PROJECT_BPM),
                        notes = (0 until notes.length()).map { k ->
                            val n = notes.getJSONArray(k)
                            NoteEvent(midiNote = n.getInt(0), startTimeMs = n.getLong(1), endTimeMs = n.getLong(2), velocity = n.getInt(3))
                        }
                    )
                }
            )
        }
    }
}


const val DEFAULT_PROJECT_BPM = 120
const val MIN_PROJECT_BPM = 40
const val MAX_PROJECT_BPM = 300

/** Garde la position en temps musicaux quand le tempo change. */
fun scaleTime(timeMs: Long, fromBpm: Int, toBpm: Int): Long =
    (timeMs.toDouble() * fromBpm / toBpm).roundToLong()
