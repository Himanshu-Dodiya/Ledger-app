package com.ledger.collector.domain.imports.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaytmParserTest {

    private val sample = """
        Paytm Statement for 11 MAR'26 - 10 JUN'26
        Total Money Paid - Rs.100 Total Money Received + Rs.9,250
        Passbook Payments History
        Date & Time Transaction Details Notes & Tags Your Account Amount
        11 May
        2:57 PM
        BRTS - 1 Ticket from Sanand Circle to Parasnagar
        UPI ID: paytm-citybus1@ptybl on
        UPI Ref No: 205903024462
        Order ID: 27015124839
        Tag: # Bill Payments
        State Bank Of India - 47
        - Rs.20
        18 Mar
        1:12 PM
        Received from Makwana Harsh Rushiraj
        UPI ID: harsh.makwana.abc@okicici on
        UPI Ref No: 607772972241
        Tag: # Money Received
        State Bank Of India - 47
        + Rs.1,100
    """.trimIndent()

    @Test fun detectsPaytm() {
        assertTrue(PaytmParser.canParse(sample))
        assertFalse(PaytmParser.canParse("Google Pay Transaction statement"))
    }

    @Test fun parsesBothRows() {
        assertEquals(2, PaytmParser.parse(sample).size)
    }

    @Test fun parsesDebit() {
        val r = PaytmParser.parse(sample).first()
        assertEquals(20.0, r.amount, 0.001)
        assertEquals(ParsedRow.Direction.DEBIT, r.direction)
        assertEquals("BRTS - 1 Ticket from Sanand Circle to Parasnagar", r.merchant)
        assertEquals("paytm-citybus1@ptybl", r.counterpartyUpi)
        assertEquals("205903024462", r.referenceId)
        assertEquals("State Bank Of India - 47", r.bankAccount)
        assertEquals("2026-05-11", r.txnDate)
        assertEquals("2026-05-11T14:57:00+05:30", r.txnTimeIso)
        assertTrue(r.note?.contains("Bill Payments") == true)
    }

    @Test fun parsesCredit() {
        val r = PaytmParser.parse(sample)[1]
        assertEquals(1100.0, r.amount, 0.001)
        assertEquals(ParsedRow.Direction.CREDIT, r.direction)
        assertEquals("Makwana Harsh Rushiraj", r.merchant)
        assertEquals("harsh.makwana.abc@okicici", r.counterpartyUpi)
        assertEquals("State Bank Of India - 47", r.bankAccount)
        assertEquals("2026-03-18", r.txnDate)
    }
}
