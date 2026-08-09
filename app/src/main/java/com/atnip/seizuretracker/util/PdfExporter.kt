package com.atnip.seizuretracker.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.atnip.seizuretracker.data.model.Household
import com.atnip.seizuretracker.data.model.Seizure
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a simple, readable seizure log as a PDF using Android's built-in PdfDocument —
 * no third-party PDF library required. Good enough to hand to a vet: a header with dog/vet
 * info, then one block per seizure, paginating automatically.
 */
object PdfExporter {

    private const val PAGE_WIDTH = 595 // A4 at 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 40f

    fun export(context: Context, household: Household?, seizures: List<Seizure>): android.net.Uri {
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

        fun drawWrapped(label: String, value: String) {
            if (value.isBlank()) return
            newPageIfNeeded(lineHeight)
            canvas.drawText("$label:", MARGIN, y, labelPaint)
            val labelWidth = labelPaint.measureText("$label: ")
            val maxWidth = PAGE_WIDTH - MARGIN * 2 - labelWidth
            val words = value.split(" ")
            var line = StringBuilder()
            var first = true
            for (word in words) {
                val trial = if (line.isEmpty()) word else "${line} $word"
                if (bodyPaint.measureText(trial) > maxWidth && line.isNotEmpty()) {
                    newPageIfNeeded(lineHeight)
                    canvas.drawText(line.toString(), MARGIN + (if (first) labelWidth else 0f), y, bodyPaint)
                    y += lineHeight
                    first = false
                    line = StringBuilder(word)
                } else {
                    line = StringBuilder(trial)
                }
            }
            if (line.isNotEmpty()) {
                newPageIfNeeded(lineHeight)
                canvas.drawText(line.toString(), MARGIN + (if (first) labelWidth else 0f), y, bodyPaint)
                y += lineHeight
            }
        }

        // Header
        drawLine((household?.dogName?.ifBlank { null } ?: "Dog") + " — Seizure Log", titlePaint)
        y += 6f
        household?.let { h ->
            if (h.dogBreed.isNotBlank()) drawLine("Breed: ${h.dogBreed}", bodyPaint)
            if (h.dogWeightKg != null) drawLine("Weight: ${h.dogWeightKg} kg", bodyPaint)
            if (h.vetName.isNotBlank() || h.vetPhone.isNotBlank()) {
                drawLine("Vet: ${h.vetName} ${h.vetPhone}".trim(), bodyPaint)
            }
            if (h.medications.isNotEmpty()) {
                drawLine("Current medications:", labelPaint)
                h.medications.forEach { m ->
                    drawLine("• ${m.name} — ${m.dose}, ${m.frequency}", bodyPaint, indent = 12f)
                }
            }
        }
        drawLine("Generated ${DateTimeUtils.formatDateTime(System.currentTimeMillis())} · ${seizures.size} seizures", bodyPaint)
        y += 10f

        val sorted = seizures.sortedByDescending { it.timestampMillis }
        for (s in sorted) {
            newPageIfNeeded(lineHeight * 3)
            y += 6f
            drawLine(DateTimeUtils.formatDateTime(s.timestampMillis), headingPaint)
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
            y += 8f
        }

        document.finishPage(page)

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = (household?.dogName?.ifBlank { null } ?: "dog").replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(exportsDir, "${safeName}_seizure_log.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
