package com.example.voicetomidi.project

import com.example.voicetomidi.notes.NoteEvent
import org.json.JSONArray
import org.json.JSONObject

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
    val muted: Boolean = false
) {
    val durationMs: Long get() = notes.maxOfOrNull { it.endTimeMs } ?: 0L
}

/** Un projet de la composition : des pistes qui jouent ensemble. */
data class Project(
    val id: Long,
    val name: String,
    val tracks: List<ProjectTrack> = emptyList()
) {
    val durationMs: Long get() = tracks.maxOfOrNull { it.durationMs } ?: 0L

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
                .put("tracks", JSONArray(project.tracks.map { track ->
                    JSONObject()
                        .put("id", track.id)
                        .put("name", track.name)
                        .put("kind", track.kind.name)
                        .put("muted", track.muted)
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
                tracks = (0 until tracks.length()).map { j ->
                    val track = tracks.getJSONObject(j)
                    val notes = track.getJSONArray("notes")
                    ProjectTrack(
                        id = track.getLong("id"),
                        name = track.getString("name"),
                        kind = TrackKind.valueOf(track.getString("kind")),
                        muted = track.optBoolean("muted", false),
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
