package com.ledger.collector.domain.imports.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Extracts plain text from a user-picked PDF using PdfBox-Android. Runs entirely on-device;
 * the file bytes never leave the phone. `sortByPosition` keeps columns roughly in reading
 * order, which the parsers' block-segmentation tolerates.
 */
class PdfTextExtractor(private val context: Context) {

    /** Returns the full document text, or null if the file can't be opened/read as a PDF. */
    fun extract(uri: Uri): String? =
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                }
            }
        } catch (_: Exception) {
            null
        }
}
