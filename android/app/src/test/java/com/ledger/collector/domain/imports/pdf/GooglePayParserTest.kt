package com.ledger.collector.domain.imports.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GooglePayParserTest {

    // Mirrors PdfBox's extracted text: amount lands on the same row as the "Paid to" line,
    // so it appears between the merchant and the UPI id. Header + footer noise is included.
    private val sample = """
        Google Pay Transaction statement 9712533030, himanshudodiya26@gmail.com
        Transaction statement period 01 March 2026 - 31 May 2026 Sent ₹2,10,040.31 Received ₹49,618.84
        Date & time Transaction details Amount
        01 Mar, 2026
        02:50 PM
        Paid to ISHWAR SINGH RAJPUT ₹34
        UPI Transaction ID: 606018398617
        Paid by State Bank of India 9347
        02 Mar, 2026
        08:12 PM
        Self transfer to RBL 9246 ₹9,000
        UPI Transaction ID: 606181317227
        Paid by State Bank of India 9347
        05 Mar, 2026
        02:31 PM
        Paid to PATEL PETROLEUM ₹300.85
        Transaction ID: 3ff9672e-968b-4997-9f8f-82d00f7d8b57
        Paid by Gpay | Mastercard credit card
        18 Mar, 2026
        01:12 PM
        Received from Makwana Harsh ₹1,100
        UPI Transaction ID: 607772972241
        Paid by State Bank of India 9347
        Note: This statement reflects payments made by you on the Google Pay app.
        Powered by UPI Page 1 of 26
    """.trimIndent()

    @Test fun detectsGooglePay() {
        assertTrue(GooglePayParser.canParse(sample))
        assertFalse(GooglePayParser.canParse("Paytm Passbook Payments History"))
    }

    @Test fun parsesAllRows() {
        val rows = GooglePayParser.parse(sample)
        assertEquals(4, rows.size)
    }

    @Test fun parsesUpiDebit() {
        val r = GooglePayParser.parse(sample).first()
        assertEquals(34.0, r.amount, 0.001)
        assertEquals(ParsedRow.Direction.DEBIT, r.direction)
        assertEquals("ISHWAR SINGH RAJPUT", r.merchant)
        assertEquals("606018398617", r.referenceId)
        assertEquals("State Bank of India 9347", r.bankAccount)
        assertEquals("UPI", r.paymentMethod)
        assertEquals("2026-03-01", r.txnDate)
        assertEquals("2026-03-01T14:50:00+05:30", r.txnTimeIso)
    }

    @Test fun parsesSelfTransfer() {
        val r = GooglePayParser.parse(sample)[1]
        assertEquals(9000.0, r.amount, 0.001)
        assertEquals("Self transfer", r.note)
    }

    @Test fun parsesCreditCard() {
        val r = GooglePayParser.parse(sample)[2]
        assertEquals(300.85, r.amount, 0.001)
        assertEquals("PATEL PETROLEUM", r.merchant)
        assertEquals("3ff9672e-968b-4997-9f8f-82d00f7d8b57", r.referenceId)
        assertEquals("Credit Card", r.paymentMethod)
    }

    @Test fun parsesCredit() {
        val r = GooglePayParser.parse(sample)[3]
        assertEquals(ParsedRow.Direction.CREDIT, r.direction)
        assertEquals("Makwana Harsh", r.merchant)
        assertNull(r.counterpartyUpi)
    }
}
