package com.ledger.collector.domain.imports.pdf

import java.util.Locale

/** Shared helpers for the statement parsers: amount/date normalization. */
internal object ParseUtil {

    private val MONTHS = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12",
    )

    /** Collapse newlines and runs of whitespace into single spaces. */
    fun flatten(s: String): String = s.replace(Regex("\\s+"), " ").trim()

    /** "2,10,040.31" / "1,100" / "34" → Double. Returns null if unparseable. */
    fun amount(raw: String): Double? =
        raw.replace(",", "").trim().toDoubleOrNull()

    fun monthNumber(mon: String): String? = MONTHS[mon.lowercase(Locale.US).take(3)]

    /** ("01", "Mar", "2026") → "2026-03-01". */
    fun isoDate(day: String, mon: String, year: String): String? {
        val mm = monthNumber(mon) ?: return null
        return "%s-%s-%02d".format(year, mm, day.trim().toInt())
    }

    /**
     * Build an RFC3339 timestamp in IST (statements are issued in India). Time is
     * "02:50 PM" style. Returns null on bad input; the date alone is still kept by callers.
     */
    fun isoTime(isoDate: String, hour12: String, minute: String, ampm: String): String? {
        val h = hour12.toIntOrNull() ?: return null
        val hour24 = when {
            ampm.uppercase(Locale.US) == "PM" && h != 12 -> h + 12
            ampm.uppercase(Locale.US) == "AM" && h == 12 -> 0
            else -> h
        }
        return "%sT%02d:%s:00+05:30".format(isoDate, hour24, minute)
    }
}
