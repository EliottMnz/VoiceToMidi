package com.example.voicetomidi.project

import com.example.voicetomidi.notes.NoteEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectJsonTest {

    @Test
    fun `projects survive a save and reload`() {
        val projects = listOf(
            Project(
                id = 1,
                name = "Mon son",
                tracks = listOf(
                    ProjectTrack(
                        id = 3,
                        name = "Mélodie 1",
                        kind = TrackKind.MELODY,
                        notes = listOf(NoteEvent(60, 0, 450, 90), NoteEvent(62, 500, 1_200, 70)),
                        muted = true
                    ),
                    ProjectTrack(id = 4, name = "Mélodie 2", kind = TrackKind.MELODY, notes = emptyList())
                )
            ),
            Project(id = 2, name = "Vide")
        )

        assertEquals(projects, ProjectJson.decode(ProjectJson.encode(projects)))
    }

    @Test
    fun `melody names stay unique after a track is removed`() {
        val project = Project(
            id = 1,
            name = "P",
            tracks = listOf(ProjectTrack(id = 2, name = "Mélodie 2", kind = TrackKind.MELODY, notes = emptyList()))
        )
        // Une seule mélodie, nommée "Mélodie 2" (la 1 a été retirée) : la suivante ne doit pas s'appeler "Mélodie 2".
        assertEquals("Mélodie 3", project.nextMelodyName())
    }
}
