package com.ledger.collector.domain.upi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the launch-URI builder — the core of the "bank limit" fix. These exercise the pure
 * string logic (no android.net.Uri), proving the original merchant QR is preserved byte-for-byte
 * and that an amount is only injected when the QR lacks one.
 */
class UpiUriTest {

    @Test
    fun `merchant QR with amount is launched verbatim — sign mc mode orgid preserved`() {
        val raw = "upi://pay?pa=merchant@okhdfcbank&pn=Coffee%20Shop&am=120.00&cu=INR" +
            "&mc=5411&mode=02&orgid=159761&tr=ABC123&sign=SOMELONGSIGNATURE=="
        // User "edits" amount to 999 — must be ignored for a QR that already pins the amount.
        val out = UpiUri.buildLaunchUri(raw, 999.0)
        assertEquals(raw, out)
        assertTrue(out.contains("sign=SOMELONGSIGNATURE=="))
        assertTrue(out.contains("mc=5411"))
        assertTrue(out.contains("mode=02"))
        assertTrue(out.contains("orgid=159761"))
    }

    @Test
    fun `static QR without amount gets am and cu appended, everything else intact`() {
        val raw = "upi://pay?pa=shop@oksbi&pn=Veggies&mc=5411&sign=ABC"
        val out = UpiUri.buildLaunchUri(raw, 250.0)
        assertEquals("upi://pay?pa=shop@oksbi&pn=Veggies&mc=5411&sign=ABC&am=250.00&cu=INR", out)
    }

    @Test
    fun `static QR with cu already present only appends am`() {
        val raw = "upi://pay?pa=p@oksbi&pn=Name&cu=INR"
        val out = UpiUri.buildLaunchUri(raw, 50.0)
        assertEquals("upi://pay?pa=p@oksbi&pn=Name&cu=INR&am=50.00", out)
    }

    @Test
    fun `no amount supplied leaves the QR untouched`() {
        val raw = "upi://pay?pa=p@oksbi&pn=Name&sign=XYZ"
        assertEquals(raw, UpiUri.buildLaunchUri(raw, null))
    }

    @Test
    fun `amount is formatted locale-independently with two decimals`() {
        assertEquals("100.00", UpiUri.trimAmount(100.0))
        assertEquals("33.34", UpiUri.trimAmount(33.34))
        assertEquals("0.50", UpiUri.trimAmount(0.5))
    }

    @Test
    fun `empty am in QR is treated as missing and filled`() {
        val raw = "upi://pay?pa=p@oksbi&am=&cu=INR"
        val out = UpiUri.buildLaunchUri(raw, 75.0)
        assertEquals("upi://pay?pa=p@oksbi&cu=INR&am=75.00", out)
    }

    // ---- real captured QRs (regression for the Google Pay "bank limit" failure) ----

    @Test
    fun `real merchant QR — mc preserved, unencoded payee-name spaces fixed, VPA @ kept`() {
        val raw = "upi://pay?pa=MSSRWATERWORLD.eazypay@icici&pn=SR WATER WORLD" +
            "&tr=EZYS9909332998&cu=INR&mc=5172"
        val out = UpiUri.buildLaunchUri(raw, 1.0)
        assertEquals(
            "upi://pay?pa=MSSRWATERWORLD.eazypay@icici&pn=SR%20WATER%20WORLD" +
                "&tr=EZYS9909332998&cu=INR&mc=5172&am=1.00",
            out,
        )
        assertTrue("mc must survive (else GPay treats P2M as P2P)", out.contains("mc=5172"))
        assertTrue("no raw spaces allowed", !out.contains(" "))
        assertTrue("VPA @ must stay literal", out.contains("eazypay@icici"))
    }

    @Test
    fun `real P2P GPay QR — proprietary aid dropped, standard params kept, am+cu appended`() {
        val raw = "upi://pay?pa=malav.solanki96.ms@okaxis&pn=Malav%20Solanki&aid=uGICAgIDV39j1Kw"
        val out = UpiUri.buildLaunchUri(raw, 10.0)
        assertEquals(
            "upi://pay?pa=malav.solanki96.ms@okaxis&pn=Malav%20Solanki&am=10.00&cu=INR",
            out,
        )
        assertTrue("non-standard aid must be dropped", !out.contains("aid"))
        assertTrue("existing %20 must not be double-encoded", !out.contains("%2520"))
    }

    @Test
    fun `standard merchant params (mc, tr, sign, mode, orgid) are all kept`() {
        val raw = "upi://pay?pa=m@icici&pn=Shop&tr=T1&cu=INR&mc=5172&mode=02&orgid=159761&sign=ABC"
        val out = UpiUri.buildLaunchUri(raw, 5.0)
        for (p in listOf("mc=5172", "tr=T1", "mode=02", "orgid=159761", "sign=ABC", "am=5.00")) {
            assertTrue("missing $p in $out", out.contains(p))
        }
    }

    @Test
    fun `sanitize encodes spaces and unicode but leaves escapes and VPA chars intact`() {
        assertEquals("a%20b", UpiUri.sanitize("a b"))
        assertEquals("Coffee%20Shop", UpiUri.sanitize("Coffee%20Shop")) // idempotent
        assertEquals("x@y.z-_", UpiUri.sanitize("x@y.z-_"))
        assertEquals("caf%C3%A9", UpiUri.sanitize("café")) // UTF-8 non-ASCII
    }
}
