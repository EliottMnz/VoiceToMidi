package com.example.voicetomidi.project

import com.example.voicetomidi.notes.NoteEvent
import org.junit.Assert.*
import org.junit.Test

class ProjectTempoTest {
    private val track = ProjectTrack(2, "Mélodie 1", TrackKind.MELODY,
        listOf(NoteEvent(60, 500, 1000, 90)), sourceBpm = 120)

    @Test fun `changer de tempo conserve les temps musicaux et les hauteurs`() {
        val faster = track.notesAtTempo(240).single()
        assertEquals(NoteEvent(60, 250, 500, 90), faster)
        assertEquals(track.notes, track.notesAtTempo(120))
        assertEquals(2000L, Project(1, "P", listOf(track), bpm = 60).durationMs)
        assertEquals(250L, scaleTime(500, 120, 240))
    }

    @Test fun `les anciennes sauvegardes conservent leurs instants`() {
        val json = """{"version":1,"projects":[{"id":1,"name":"Ancien","tracks":[
            {"id":2,"name":"Mélodie 1","kind":"MELODY","notes":[[60,500,1000,90]]}]}]}"""
        val project = ProjectJson.decode(json).single()
        assertEquals(120, project.bpm)
        assertEquals(track.notes, project.tracks.single().notesAtTempo(project.bpm))
    }

    @Test fun `le tempo du projet et celui de chaque prise sont sauvegardes`() {
        val project = Project(1, "P", listOf(track.copy(sourceBpm = 90)), bpm = 145)
        assertEquals(listOf(project), ProjectJson.decode(ProjectJson.encode(listOf(project))))
    }

    @Test fun `le calage reste sur la grille apres un changement de tempo`() {
        val loose = track.copy(notes = listOf(NoteEvent(64, 144, 391, 88)))
        val aligned = loose.alignedToGrid()
        assertEquals(NoteEvent(64, 125, 375, 88), aligned.notes.single())
        assertEquals(NoteEvent(64, 250, 750, 88), aligned.notesAtTempo(60).single())
        assertEquals(aligned, aligned.alignedToGrid())
        assertEquals(144L, loose.notes.single().startTimeMs)
    }
}
