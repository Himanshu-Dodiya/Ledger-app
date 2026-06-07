package com.ledger.collector.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small coloured chip showing where a transaction came from: SMS (blue) or Gmail (green).
 * Mirrors the web inbox badges so the two surfaces feel consistent.
 */
@Composable
fun SourceBadge(source: String, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (source.lowercase()) {
        "sms" -> Triple("SMS", Color(0xFFDBEAFE), Color(0xFF1D4ED8))
        "gmail" -> Triple("GMAIL", Color(0xFFD1FAE5), Color(0xFF047857))
        else -> Triple(source.uppercase(), Color(0xFFE5E7EB), Color(0xFF374151))
    }
    Text(
        text = label,
        color = fg,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
