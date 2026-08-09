package com.atnip.seizuretracker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression test for the "ViewModelStore key collision" bug described in CLAUDE.md: two
 * `viewModel(key = ...)` calls in AppRoot.kt sharing a key silently clobber each other, because
 * [ViewModelProvider.get] falls through to creating (and storing) a brand-new instance whenever
 * the value already stored under a key isn't an instance of the requested class — it does not
 * throw. That's how "household data never loading" happened: a second, unrelated ViewModel type
 * requested under the same key silently evicted the first one's already-loaded state.
 *
 * This test exercises that exact ViewModelStore/ViewModelProvider mechanism directly (with two
 * minimal stand-in ViewModel types) rather than the real HouseholdViewModel/SeizureListViewModel,
 * since those eagerly touch Firestore on construction and need the Robolectric+emulator setup
 * from Phase 3 to instantiate at all.
 */
class ViewModelKeyCollisionTest {

    private class FakeHouseholdViewModel : ViewModel()
    private class FakeSeizureListViewModel : ViewModel()

    private fun factoryFor(implClass: Class<out ViewModel>): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                implClass.getDeclaredConstructor().newInstance() as T
        }

    @Test
    fun `distinct per-type keys keep both viewmodels stable across recomposition`() {
        val store = ViewModelStore()

        val household1 = ViewModelProvider(store, factoryFor(FakeHouseholdViewModel::class.java))
            .get("household_abc", FakeHouseholdViewModel::class.java)
        val seizureList1 = ViewModelProvider(store, factoryFor(FakeSeizureListViewModel::class.java))
            .get("seizureList_abc", FakeSeizureListViewModel::class.java)

        // Simulate a later recomposition re-requesting both by their own distinct keys.
        val household2 = ViewModelProvider(store, factoryFor(FakeHouseholdViewModel::class.java))
            .get("household_abc", FakeHouseholdViewModel::class.java)
        val seizureList2 = ViewModelProvider(store, factoryFor(FakeSeizureListViewModel::class.java))
            .get("seizureList_abc", FakeSeizureListViewModel::class.java)

        assertSame("HouseholdViewModel should survive under its own key", household1, household2)
        assertSame("SeizureListViewModel should survive under its own key", seizureList1, seizureList2)
    }

    @Test
    fun `sharing one key across two viewmodel types silently evicts the first`() {
        val store = ViewModelStore()
        val sharedKey = "household_abc" // the bug: both call sites used this same key

        val household1 = ViewModelProvider(store, factoryFor(FakeHouseholdViewModel::class.java))
            .get(sharedKey, FakeHouseholdViewModel::class.java)

        // A second, different ViewModel type requested under the SAME key.
        ViewModelProvider(store, factoryFor(FakeSeizureListViewModel::class.java))
            .get(sharedKey, FakeSeizureListViewModel::class.java)

        // Re-requesting the household ViewModel under that key now silently creates a *new*
        // instance instead of returning the original — the original's already-loaded state
        // (e.g. household data from Firestore) is gone. This is the "never loading" bug.
        val household2 = ViewModelProvider(store, factoryFor(FakeHouseholdViewModel::class.java))
            .get(sharedKey, FakeHouseholdViewModel::class.java)

        assertNotSame(
            "expected the key collision to have silently replaced the original instance",
            household1,
            household2
        )
    }
}
