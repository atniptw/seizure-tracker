package com.atnip.seizuretracker.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.atnip.seizuretracker.data.model.Pet
import com.atnip.seizuretracker.data.model.Vet
import com.atnip.seizuretracker.data.model.PetVetLink
import com.atnip.seizuretracker.ui.common.Entry
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a readable seizure/health-note log as a PDF using Android's built-in PdfDocument — no
 * third-party PDF library required. One or more pets' entries can be included in one report; each
 * pet gets its own header block (profile, medications, linked vets), and entry blocks are tagged
 * with the pet's name when more than one pet is included.
 *
 * No unit test exercises this directly: Robolectric ships no shadow for `PdfDocument`, so it
 * falls through to the real Android SDK source, which needs native PDF-writing JNI calls that
 * don't exist on the desktop JVM (`document.startPage()` throws `IllegalStateException: document
 * is closed!` on the very first call, in complete isolation). [ExportFilenames] — the one piece
 * of logic this shares with [CsvExporter] — is unit-tested instead; the rendering itself is
 * verified by live-generating a PDF on a real device/emulator.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595 // A4 at 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    fun export(
        context: Context,
        pets: List<Pet>,
        entries: List<Entry>,
        vets: List<Vet>,
        links: List<PetVetLink>
    ): android.net.Uri {
        val document = PdfDocument()
        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true }
        val headingPaint = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 11f }
        val labelPaint = Paint().apply { textSize = 11f; isFakeBoldText = true }
        val lineHeight = 16f

        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create())
        var canvas: Canvas = page.canvas
        var y = MARGIN

        fun newPageIfNeeded(extraHeight: Float) {
            if (y + extraHeight > PAGE_HEIGHT - MARGIN) {
                document.finishPage(page)
                page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, document.pages.size + 1).create())
                canvas = page.canvas
                y = MARGIN
            }
        }

        fun drawLine(text: String, paint: Paint, indent: Float = 0f) {
            newPageIfNeeded(lineHeight)
            canvas.drawText(text, MARGIN + indent, y, paint)
            y += lineHeight
        }

        fun drawWrapped(label: String, value: String, indent: Float = 0f) {
            if (value.isBlank()) return
            newPageIfNeeded(lineHeight)
            canvas.drawText("$label:", MARGIN + indent, y, labelPaint)
            val labelWidth = labelPaint.measureText("$label: ")
            val maxWidth = PAGE_WIDTH - MARGIN * 2 - indent - labelWidth
            val words = value.split(" ")
            var line = StringBuilder()
            var first = true
            for (word in words) {
                val trial = if (line.isEmpty()) word else "${line} $word"
                if (bodyPaint.measureText(trial) > maxWidth && line.isNotEmpty()) {
                    newPageIfNeeded(lineHeight)
                    canvas.drawText(line.toString(), MARGIN + indent + (if (first) labelWidth else 0f), y, bodyPaint)
                    y += lineHeight
                    first = false
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(trial)
                }
            }
            if (line.isNotEmpty()) {
                newPageIfNeeded(lineHeight)
                canvas.drawText(line.toString(), MARGIN + indent + (if (first) labelWidth else 0f), y, bodyPaint)
                y += lineHeight
            }
        }

        // Header
        val title = if (pets.size == 1) {
            (pets[0].name.ifBlank { "Pet" }) + " — Seizure Log"
        } else {
            (pets.joinToString(" & ") { it.name.ifBlank { "Pet" } }.ifBlank { "Pets" }) + " — Seizure Log"
        }
        drawLine(title, titlePaint)
        y += 6f
        pets.forEach { p ->
            if (pets.size > 1) drawLine(p.name.ifBlank { "Pet" }, headingPaint)
            val indent = if (pets.size > 1) 12f else 0f
            if (p.breed.isNotBlank()) drawLine("Breed: ${p.breed}", bodyPaint, indent)
            if (p.weightKg != null) drawLine("Weight: ${p.weightKg} kg", bodyPaint, indent)
            if (p.medications.isNotEmpty()) {
                drawLine("Current medications:", labelPaint, indent)
                p.medications.forEach { m -> drawLine("• ${m.name} — ${m.dose}, ${m.frequency}", bodyPaint, indent + 12f) }
            }
            val petVets = links.filter { it.petId == p.id }
                .mapNotNull { link -> vets.find { it.id == link.vetId }?.let { it to link.role } }
            if (petVets.isNotEmpty()) {
                drawLine("Vets:", labelPaint, indent)
                petVets.forEach { (vet, role) ->
                    val phone = if (vet.phone.isNotBlank()) ", ${vet.phone}" else ""
                    drawLine("• $role — ${vet.name}$phone", bodyPaint, indent + 12f)
                }
            }
            y += 4f
        }
        drawLine("Generated ${DateTimeUtils.formatDateTime(System.currentTimeMillis())} · ${entries.size} entries", bodyPaint)
        y += 10f

        val petById = pets.associateBy { it.id }
        val sorted = entries.sortedByDescending { it.timestampMillis }
        for (entry in sorted) {
            newPageIfNeeded(lineHeight * 3)
            y += 6f
            val petPrefix = if (pets.size > 1) "${petById[entry.petId]?.name ?: "Pet"} — " else ""
            when (entry) {
                is Entry.SeizureEntry -> {
                    val s = entry.seizure
                    drawLine("$petPrefix${DateTimeUtils.formatDateTime(s.timestampMillis)} · Seizure", headingPaint)
                    drawWrapped("Duration", DateTimeUtils.formatDuration(s.durationSeconds))
                    drawWrapped("Type", s.seizureType)
                    drawWrapped("Symptoms", s.symptoms.joinToString(", "))
                    drawWrapped("Signs before onset", s.preSeizureSigns)
                    drawWrapped("Possible triggers", s.possibleTriggers)
                    drawWrapped("Recovery time", s.recoveryMinutes?.let { "$it min" } ?: "")
                    drawWrapped("Recovery behavior", s.recoveryNotes)
                    drawWrapped("Rescue medication", if (s.rescueMedGiven) "Yes — ${s.rescueMedDetails}" else "No")
                    drawWrapped("Notes", s.notes)
                    drawWrapped("Logged by", s.loggedByName)
                }
                is Entry.NoteEntry -> {
                    val n = entry.note
                    drawLine("$petPrefix${DateTimeUtils.formatDateTime(n.timestampMillis)} · Health note", headingPaint)
                    drawWrapped("Description", n.description)
                    drawWrapped("Notes", n.notes)
                    drawWrapped("Flagged for vet", if (n.flaggedForVet) "Yes" else "No")
                    drawWrapped("Logged by", n.loggedByName)
                }
            }
            y += 8f
        }

        document.finishPage(page)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, "${ExportFilenames.build(pets, entries)}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
