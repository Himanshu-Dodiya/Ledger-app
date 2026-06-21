package com.ledger.collector.domain.upi

import android.net.Uri
import java.util.Locale

/**
 * A parsed UPI intent link (BharatQR / UPI QR codes encode `upi://pay?...`). Common params:
 * pa (payee VPA), pn (payee name), am (amount), cu (currency), tn (note), tr (transaction ref),
 * mc (merchant code), mode, orgid, sign (merchant signature) and others.
 *
 * IMPORTANT (root cause of the "bank limit" failure): a merchant / P2M QR is a *signed* intent —
 * its `sign` value digitally signs the payee + merchant params. Earlier we reconstructed the
 * launch URI from only a handful of parsed fields (pa/pn/am/cu/tn/tr), which silently dropped
 * `mc`, `mode`, `orgid` and `sign`. Google Pay then forwarded an unsigned/reclassified request to
 * the bank, which rejected it with the misleading "You've exceeded the bank limit…" message —
 * even though paying the original QR directly in Google Pay worked. The fix is to launch the
 * *original scanned string verbatim* and only inject `am` when the QR carried no amount.
 *
 * Reality: UPI apps do not reliably return a result to a non-PSP caller, so the caller should
 * record the transaction optimistically and rely on SMS/statement reconciliation (dedupe by
 * the same UPI ref) rather than a guaranteed callback.
 */
data class UpiUri(
    val payeeVpa: String,
    val payeeName: String?,
    val amount: Double?,
    val note: String?,
    val txnRef: String?,
    val currency: String = "INR",
    /** The exact scanned string, preserved so the launch request is byte-faithful to the QR. */
    val raw: String = "",
    /** Every query parameter the QR carried (key -> value), for debugging / inspection. */
    val params: Map<String, String> = emptyMap(),
) {
    /** True when the QR already pins an amount (dynamic/merchant QR); that amount must not change. */
    val hasFixedAmount: Boolean get() = amount != null

    /**
     * Build the launchable upi://pay link. Preserves the original query verbatim (including
     * `mc`, `mode`, `orgid`, `sign`, `tr`) and only appends `am` (and `cu` if missing) when the
     * QR didn't already carry an amount. [note] is intentionally NOT injected — altering a signed
     * merchant intent would invalidate its signature.
     */
    fun toIntentUri(amount: Double?, @Suppress("UNUSED_PARAMETER") note: String? = null): Uri =
        Uri.parse(buildLaunchUri(raw.ifBlank { "upi://pay?pa=$payeeVpa" }, amount))

    companion object {
        /** Parse a scanned string; returns null if it isn't a UPI payment link with a payee. */
        fun parse(raw: String): UpiUri? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("upi://", ignoreCase = true) &&
                !trimmed.startsWith("upi:", ignoreCase = true)
            ) return null
            return runCatching {
                val uri = Uri.parse(trimmed)
                val pa = uri.getQueryParameter("pa")?.trim().orEmpty()
                if (pa.isBlank()) return null
                val params = LinkedHashMap<String, String>()
                for (name in uri.queryParameterNames) {
                    uri.getQueryParameter(name)?.let { params[name] = it }
                }
                UpiUri(
                    payeeVpa = pa,
                    payeeName = uri.getQueryParameter("pn")?.trim()?.ifBlank { null },
                    amount = uri.getQueryParameter("am")?.trim()?.toDoubleOrNull(),
                    note = uri.getQueryParameter("tn")?.trim()?.ifBlank { null },
                    txnRef = uri.getQueryParameter("tr")?.trim()?.ifBlank { null },
                    currency = uri.getQueryParameter("cu")?.trim()?.ifBlank { null } ?: "INR",
                    raw = trimmed,
                    params = params,
                )
            }.getOrNull()
        }

        /**
         * Pure string builder for the launch URI — kept free of android.net.Uri so it can be
         * unit-tested on the JVM. Preserves every parameter the QR carried (so merchant context
         * like `mc`/`tr`/`sign` survives) and only appends `am` (and `cu` if absent) when the QR
         * had no amount. The final string is run through [sanitize] so literal spaces and other
         * URI-unsafe characters become valid `%XX` escapes — many real merchant QRs ship an
         * unencoded payee name (e.g. `pn=SR WATER WORLD`), which is an invalid URI that can break
         * the payment downstream. Existing `%XX` escapes are left intact (never double-encoded).
         */
        // The NPCI UPI deep-linking parameters every UPI app must honour. We forward only these
        // and drop app-proprietary ones (e.g. Google Pay's `aid`), which a third-party `upi://pay`
        // intent should not carry — forwarding them can make the receiving app reject the request.
        private val STANDARD_PARAMS = setOf(
            "pa", "pn", "mc", "tid", "tr", "tn", "am", "mam", "cu", "url", "mode", "orgid", "sign", "purpose",
        )

        @JvmStatic
        fun buildLaunchUri(raw: String, amount: Double?): String {
            val qIndex = raw.indexOf('?')
            val base = if (qIndex < 0) raw else raw.substring(0, qIndex)
            val query = if (qIndex < 0) "" else raw.substring(qIndex + 1)
            val allPairs = query.split('&').filter { it.isNotEmpty() }

            fun keyOf(p: String) = p.substringBefore('=', p)
            fun valOf(p: String) = if (p.contains('=')) p.substringAfter('=') else ""

            // Keep only standard UPI params, in their original order, dropping any blank `am=`.
            val pairs = allPairs.filter {
                val k = keyOf(it).lowercase()
                k in STANDARD_PARAMS && !(k == "am" && valOf(it).isBlank())
            }

            val hasAm = pairs.any { keyOf(it).equals("am", ignoreCase = true) && valOf(it).isNotBlank() }
            val hasCu = pairs.any { keyOf(it).equals("cu", ignoreCase = true) }

            val finalPairs = if (hasAm || amount == null) {
                pairs // QR pins the amount (don't alter it) or we have nothing to add
            } else {
                pairs + buildList {
                    add("am=" + trimAmount(amount))
                    if (!hasCu) add("cu=INR")
                }
            }
            return sanitize(base + "?" + finalPairs.joinToString("&"))
        }

        // Characters that are unsafe in a URI and must be percent-encoded. We deliberately leave
        // structural/value chars (& = ? @ . - _ : / + ~ % …) alone so VPAs, signatures and
        // existing %XX escapes are preserved byte-for-byte.
        private const val UNSAFE = " \"<>\\^`{}|"

        /** Percent-encode only URI-unsafe characters (space, controls, non-ASCII) — UTF-8, idempotent on %XX. */
        @JvmStatic
        fun sanitize(uri: String): String = buildString(uri.length + 8) {
            for (ch in uri) {
                val code = ch.code
                if (ch in UNSAFE || code <= 0x20 || code >= 0x7F) {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        append('%')
                        append("%02X".format(b.toInt() and 0xFF))
                    }
                } else {
                    append(ch)
                }
            }
        }

        /** Format an amount per the UPI spec: a plain decimal with two places, locale-independent. */
        @JvmStatic
        fun trimAmount(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
    }
}
