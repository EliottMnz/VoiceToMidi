package com.example.voicetomidi.notes

data class NoteEvent(
    val midiNote: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val velocity: Int
) {
    val durationMs: Long get() = endTimeMs - startTimeMs
}
