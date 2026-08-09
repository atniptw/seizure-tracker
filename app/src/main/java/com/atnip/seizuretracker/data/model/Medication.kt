package com.atnip.seizuretracker.data.model

/**
 * A maintenance medication the dog is currently prescribed (e.g. phenobarbital, potassium
 * bromide). Distinct from a "rescue" medication given during/after a seizure, which is recorded
 * directly on the [Seizure] entry instead.
 */
data class Medication(
    val name: String = "",
    val dose: String = "",
    val frequency: String = "",
    val notes: String = ""
)
