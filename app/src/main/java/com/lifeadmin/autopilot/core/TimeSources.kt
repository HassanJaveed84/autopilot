package com.lifeadmin.autopilot.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Injectable wall clock (UTC instants). Tests use a mutable fake. */
fun interface AppClock {
    fun now(): Instant

    companion object {
        fun system(clock: Clock = Clock.systemUTC()): AppClock = AppClock { clock.instant() }
    }
}

/** Injectable device time-zone source; changes are delivered via ACTION_TIMEZONE_CHANGED. */
fun interface ZoneProvider {
    fun current(): ZoneId

    companion object {
        val SYSTEM: ZoneProvider = ZoneProvider { ZoneId.systemDefault() }
    }
}

/** UUID string ids, injectable for deterministic tests. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val RANDOM: IdGenerator = IdGenerator { java.util.UUID.randomUUID().toString() }
    }
}
