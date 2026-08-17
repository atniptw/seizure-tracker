package com.atnip.seizuretracker.data.model

import com.google.firebase.firestore.DocumentId

/** Common seizure types, shown as a picker. "Unknown / not sure" is always a valid answer. */
object SeizureTypes {
    val ALL = listOf(
        "Generalized (grand mal)",
        "Focal (partial)",
        "Petit mal / absence",
        "Cluster (multiple in 24h)",
        "Unknown / not sure"
    )
}

/** Common observable symptoms, shown as a checklist so logging is fast during a stressful moment. */
object SeizureSymptoms {
    val ALL = listOf(
        "Loss of consciousness",
        "Collapsed / fell over",
        "Paddling or convulsing limbs",
        "Muscle stiffness / rigidity",
        "Twitching",
        "Drooling / foaming",
        "Vocalizing (whining, barking)",
        "Loss of bladder control",
        "Loss of bowel control",
        "Vomiting",
        "Disorientation before falling"
    )
}

data class Seizure(
    @DocumentId val id: String = "",
    val petId: String = "",
    val timestampMillis: Long = 0L,
    val durationSeconds: Long = 0L,
    val seizureType: String = "",
    val symptoms: List<String> = emptyList(),
    val preSeizureSigns: String = "",
    val possibleTriggers: String = "",
    val recoveryMinutes: Long? = null,
    val recoveryNotes: String = "",
    val rescueMedGiven: Boolean = false,
    val rescueMedDetails: String = "",
    val notes: String = "",
    val loggedByName: String = "",
    val loggedByUid: String = "",
    val createdAtMillis: Long = 0L
)
