package com.lifeadmin.autopilot

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.domain.capture.CaptureTiming
import com.lifeadmin.autopilot.domain.model.DeadlineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The capture timing reader on a real device runtime. Android compiles regular expressions with
 * ICU, which differs from the JVM that the unit tests use (CI run #170: a "[:.]" set compiled on
 * the JVM but threw on the emulator). Same phrases and expectations as CaptureTimingTest.
 */
@RunWith(AndroidJUnit4::class)
class CaptureTimingDeviceTest {
    private val zone = ZoneId.of("America/Chicago")
    private val now = LocalDateTime.of(2026, 10, 23, 14, 0).atZone(zone).toInstant() // Fri 14:00
    private val bedtime = LocalTime.of(21, 30)
    private fun read(text: String) = CaptureTiming.read(text, now, zone, bedtime)
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant()

    @Test fun milestonePhrasesReadTheSameOnDevice() {
        assertEquals(at(23, 21, 30), (read("Turn off fan before bed").nudge as CaptureTiming.Nudge.Once).at)
        assertTrue(read("Check daughter's blanket before bed").nudge is CaptureTiming.Nudge.Once)
        assertEquals(listOf(bedtime), (read("Check daughter's blanket every night").nudge as CaptureTiming.Nudge.Daily).times)
        val work = read("Complete work update by Monday 5pm")
        assertEquals(DeadlineType.EXACT, work.deadline!!.spec.type)
        assertEquals(at(26, 17), work.deadline!!.spec.instant)
        assertTrue(read("Buy a gift for someone").isEmpty)
    }

    @Test fun everyTimeFormOnDevice() {
        assertEquals(at(23, 22), (read("Call mum tonight at 10pm").nudge as CaptureTiming.Nudge.Once).at)
        assertEquals(at(23, 22), (read("Call mum tonight at 10").nudge as CaptureTiming.Nudge.Once).at)
        assertEquals(at(23, 19, 30), (read("Take bins out this evening at 7:30").nudge as CaptureTiming.Nudge.Once).at)
        assertEquals(at(23, 19, 30), (read("Water plants 19.30").nudge as CaptureTiming.Nudge.Once).at)
        assertEquals(at(24, 12), (read("Lunch with Sam noon").nudge as CaptureTiming.Nudge.Once).at)
        assertEquals(at(27, 18), (read("Gym Tuesday 6 p.m.").nudge as CaptureTiming.Nudge.Once).at)
        assertTrue(read("Call the dentist at 7").notes.single().contains("morning or evening"))
        assertEquals(DeadlineType.DATE_ONLY, read("Pay the bill by Monday 7:30").deadline!!.spec.type)
    }
}
