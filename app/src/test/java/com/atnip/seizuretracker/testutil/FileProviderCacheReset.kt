package com.atnip.seizuretracker.testutil

import androidx.core.content.FileProvider

/**
 * Robolectric gives each test method a fresh [android.content.Context] (and thus a fresh
 * `cacheDir`), but `FileProvider.getUriForFile` caches its resolved path roots in a *static*
 * `sCache` map keyed only by authority — so once any earlier test in the same JVM fork resolves a
 * Uri, every later test's call throws `IllegalArgumentException: Failed to find configured root
 * that contains ...`, because the cached roots still point at that earlier test's now-stale
 * cacheDir. See https://github.com/robolectric/robolectric/issues/8773. Call this from a
 * `@Before` in any test that exercises [com.atnip.seizuretracker.util.PdfExporter.export] or
 * [com.atnip.seizuretracker.util.CsvExporter.export] (directly, or via
 * [com.atnip.seizuretracker.ui.export.ExportViewModel.generate]) so each test resolves fresh
 * against its own cacheDir.
 */
fun resetFileProviderCache() {
    val field = FileProvider::class.java.getDeclaredField("sCache")
    field.isAccessible = true
    (field.get(null) as MutableMap<*, *>).clear()
}
