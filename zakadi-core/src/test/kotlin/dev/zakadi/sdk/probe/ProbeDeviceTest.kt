@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.EncoderPreference
import dev.zakadi.sdk.device.CapabilityProbe
import dev.zakadi.sdk.device.CheckOutcome
import dev.zakadi.sdk.device.DeviceCaps
import dev.zakadi.sdk.device.DeviceTier
import dev.zakadi.sdk.device.ProbeCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `device` line's facts from the capability probe of spec 07 section 7.26, and its names. */
class ProbeDeviceTest {
    @Test
    fun theTierAndChecksComeFromTheCapabilityProbe() {
        val s = device(report())
        assertEquals("S", s.tier)
        assertEquals(false, s.lowRam)
        assertEquals(3072L, s.memMb)
        assertEquals("LIMITED", s.cameraLevel)
        assertEquals(true, s.eglRecordable)
        assertEquals(true, s.frontCamera)
        assertEquals(87.5, s.configureMs!!, 1e-9)
        assertEquals("L", device(report(lowRam = true)).tier)
        assertEquals("L", device(report(totalMem = 1L shl 30)).tier)
        val noEncoder = device(report(encoderPassed = false))
        assertEquals("U", noEncoder.tier)
        assertNull("a failed check is not a configure time", noEncoder.configureMs)
    }

    @Test
    fun aCameraLevelNotRunIsNull() {
        val base = report()
        val checks =
            base.checks.map {
                if (it.name == CapabilityProbe.CAMERA_LEVEL)
                    ProbeCheck(it.name, CheckOutcome.NOT_RUN, "no front camera")
                else it
            }
        val facts = base.facts.copy(frontCamera = false)
        val device = device(base.copy(checks = checks, facts = facts))
        assertNull(device.cameraLevel)
        assertEquals(false, device.frontCamera)
        assertEquals("U", device.tier)
    }

    @Test
    fun namesFollowSpec07() {
        assertEquals(
            listOf("nominal", "nominal", "fair", "serious", "critical", "critical", "critical"),
            (0..6).map(::thermalName),
        )
        assertNull(thermalName(-1))
        assertEquals("3.1", avcLevelName(0x200))
        assertEquals("1b", avcLevelName(0x02))
        assertEquals("5.2", avcLevelName(0x10000))
        assertNull(avcLevelName(0x03))
        assertEquals("constrained_baseline", avcProfileName(0x10000))
        assertEquals("baseline", avcProfileName(1))
        assertNull(avcProfileName(8))
        assertEquals("cbr", bitrateModeName(2))
        assertEquals("vbr", bitrateModeName(1))
        assertNull(bitrateModeName(0))
    }

    @Test
    fun theArgsCarryTheCapsUnapplied() {
        val caps = DeviceCaps(DeviceTier.L, 2, 15, CapturePath.B, EncoderPreference.HARDWARE, true)
        assertEquals(
            """{"encoder":"software","path":"B","divisor":1,"caps":{"best_rung":2,"max_fps":15,""" +
                """"path":"B","prefer_software_encoder":false}}""",
            LogJson.encode(ProbeArgs(EncoderPreference.SOFTWARE, CapturePath.B).json(caps)),
        )
        assertEquals(
            """{"encoder":"hardware","path":"A","divisor":10,"caps":null}""",
            LogJson.encode(ProbeArgs(divisor = 10).json(null)),
        )
    }
}
