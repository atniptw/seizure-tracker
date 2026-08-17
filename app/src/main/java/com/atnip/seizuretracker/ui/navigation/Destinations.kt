package com.atnip.seizuretracker.ui.navigation

object Destinations {
    const val DASHBOARD = "dashboard"
    const val ENTRY_HISTORY = "entry_history"
    const val EXPORT = "export"
    const val EXPORT_READY = "export_ready"

    const val ACCESSIBILITY = "accessibility"

    const val ADD_SEIZURE = "add_seizure"
    const val EDIT_SEIZURE = "edit_seizure/{seizureId}"
    fun editSeizure(seizureId: String) = "edit_seizure/$seizureId"

    const val SEIZURE_DETAIL = "seizure_detail/{seizureId}"
    fun seizureDetail(seizureId: String) = "seizure_detail/$seizureId"

    const val ADD_HEALTH_NOTE = "add_health_note/{petId}"
    fun addHealthNote(petId: String) = "add_health_note/$petId"

    const val EDIT_HEALTH_NOTE = "edit_health_note/{noteId}"
    fun editHealthNote(noteId: String) = "edit_health_note/$noteId"

    const val MANAGE_PETS = "manage_pets"
    const val ADD_PET = "add_pet"
    const val EDIT_PET = "edit_pet/{petId}"
    fun editPet(petId: String) = "edit_pet/$petId"

    const val VETS = "vets"
    const val VET_DETAIL = "vet_detail/{vetId}"
    fun vetDetail(vetId: String) = "vet_detail/$vetId"

    const val ADD_VET = "add_vet"

    // Reached from a pet's "Link a vet" sheet's "Add a new vet" row — creates the vet and links
    // it to this pet (with the default "General" role, editable afterward) in one flow.
    const val ADD_VET_FOR_PET = "add_vet_for_pet/{petId}"
    fun addVetForPet(petId: String) = "add_vet_for_pet/$petId"

    const val SETTINGS = "settings"
    const val HOUSEHOLD = "household"
}
