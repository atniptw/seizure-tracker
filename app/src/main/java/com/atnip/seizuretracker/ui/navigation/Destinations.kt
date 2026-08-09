package com.atnip.seizuretracker.ui.navigation

object Destinations {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val DOG_PROFILE = "dog_profile"
    const val EXPORT = "export"

    const val ADD_SEIZURE = "add_seizure"
    const val EDIT_SEIZURE = "edit_seizure/{seizureId}"
    fun editSeizure(seizureId: String) = "edit_seizure/$seizureId"

    const val SEIZURE_DETAIL = "seizure_detail/{seizureId}"
    fun seizureDetail(seizureId: String) = "seizure_detail/$seizureId"
}
