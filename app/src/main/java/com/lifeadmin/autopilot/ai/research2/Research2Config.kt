package com.lifeadmin.autopilot.ai.research2

/** Limits still needed to read saved M3A.2 sessions and their backups. */
object Research2Config {
    const val MAX_PAYLOAD_BYTES = 200_000

    /** A price older than this is shown as "may be stale". */
    val PRICE_STALE_AFTER: java.time.Duration = java.time.Duration.ofHours(24)
}
