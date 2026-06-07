package com.ledger.collector.work

/** User-selectable background sync cadence. WorkManager's periodic minimum is 15 min. */
enum class SyncInterval(val minutes: Long, val label: String) {
    MIN_15(15, "Every 15 minutes"),
    MIN_30(30, "Every 30 minutes"),
    HOUR_1(60, "Every hour"),
    HOUR_6(360, "Every 6 hours"),
    MANUAL(0, "Manual only");

    val isPeriodic: Boolean get() = this != MANUAL

    companion object {
        fun fromName(name: String?): SyncInterval =
            entries.firstOrNull { it.name == name } ?: MIN_30
    }
}
