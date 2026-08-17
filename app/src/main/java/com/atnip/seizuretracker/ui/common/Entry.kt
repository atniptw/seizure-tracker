package com.atnip.seizuretracker.ui.common

import com.atnip.seizuretracker.data.model.HealthNote
import com.atnip.seizuretracker.data.model.Seizure

/**
 * A merged, chronologically-sortable view over seizures and health notes for one pet's "recent
 * entries" feed. Firestore can't `ORDER BY` across the two source collections, so each is kept
 * ordered server-side and merged client-side into this type instead — see
 * [com.atnip.seizuretracker.data.repository.SeizureRepository.observeSeizures] and
 * [com.atnip.seizuretracker.data.repository.HealthNoteRepository.observeHealthNotes].
 */
sealed interface Entry {
    val timestampMillis: Long
    val petId: String

    data class SeizureEntry(val seizure: Seizure) : Entry {
        override val timestampMillis: Long get() = seizure.timestampMillis
        override val petId: String get() = seizure.petId
    }

    data class NoteEntry(val note: HealthNote) : Entry {
        override val timestampMillis: Long get() = note.timestampMillis
        override val petId: String get() = note.petId
    }
}
