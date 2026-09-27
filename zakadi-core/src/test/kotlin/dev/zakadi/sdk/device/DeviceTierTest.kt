@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.EncoderPreference
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceTierTest {
    private val gib = 1024L * 1024 * 1024
    private val good =
        DeviceFacts(
            frontCamera = true,
            avcEncoder = true,
            lowRamDevice = false,
            totalMemBytes = 3 * gib,
            legacyCamera = false,
            eglRecordable = true,
        )
    private val tecno = DeviceIdentity("TECNO", "KI5q", 11)

    @Test
    fun tierSIsTheFullLadderOnPathA() {
        assertEquals(
            DeviceCaps(DeviceTier.S, 0, null, CapturePath.A, EncoderPreference.HARDWARE, false),
            deviceCaps(good),
        )
    }

    @Test
    fun tierUHasNoFrontCameraOrNoEncoder() {
        assertEquals(DeviceTier.U, deviceCaps(good.copy(frontCamera = false)).tier)
        assertEquals(DeviceTier.U, deviceCaps(good.copy(avcEncoder = false)).tier)
        assertEquals(null, deviceCaps(good.copy(avcEncoder = false)).path)
    }

    @Test
    fun tierLIsLowRamUnder2GbOrALegacyCamera() {
        val l = DeviceCaps(DeviceTier.L, 2, 15, CapturePath.A, EncoderPreference.HARDWARE, true)
        assertEquals(l, deviceCaps(good.copy(lowRamDevice = true)))
        assertEquals(l, deviceCaps(good.copy(totalMemBytes = 1_900_000_000)))
        assertEquals(l, deviceCaps(good.copy(totalMemBytes = 2 * gib - 1)))
        assertEquals(l, deviceCaps(good.copy(legacyCamera = true)))
        assertEquals(DeviceTier.S, deviceCaps(good.copy(totalMemBytes = 2 * gib)).tier)
    }

    @Test
    fun pathBWhereEglFailed() {
        assertEquals(CapturePath.B, deviceCaps(good.copy(eglRecordable = false)).path)
        assertEquals(
            CapturePath.B,
            deviceCaps(good.copy(eglRecordable = false, lowRamDevice = true)).path,
        )
    }

    @Test
    fun aMatchingQuirkTightens() {
        val s = deviceCaps(good)
        val quirk =
            DeviceQuirk(platform = "android", modelPrefix = "TECNO KI5", maxFps = 12, maxRung = 3)
        val tightened = s.tightenedBy(listOf(quirk), tecno)
        assertEquals(3, tightened.bestRung)
        assertEquals(12, tightened.maxFps)
        assertEquals(DeviceTier.S, tightened.tier)
        val software = s.tightenedBy(listOf(DeviceQuirk(preferSoftwareEncoder = true)), tecno)
        assertEquals(EncoderPreference.SOFTWARE, software.encoder)
    }

    @Test
    fun aQuirkNeverLoosens() {
        val l = deviceCaps(good.copy(lowRamDevice = true))
        val loose = DeviceQuirk(maxFps = 30, maxRung = 0, preferSoftwareEncoder = false)
        assertEquals(l, l.tightenedBy(listOf(loose), tecno))
        val u = deviceCaps(good.copy(avcEncoder = false))
        assertEquals(u, u.tightenedBy(listOf(DeviceQuirk(preferSoftwareEncoder = true)), tecno))
    }

    @Test
    fun onlyMatchingQuirksCount() {
        val s = deviceCaps(good)
        val quirks =
            listOf(
                DeviceQuirk(platform = "web", maxFps = 10),
                DeviceQuirk(modelPrefix = "Infinix", maxRung = 4),
                DeviceQuirk(modelPrefix = "TECNO", osMajorMin = 12, maxRung = 4),
                DeviceQuirk(modelPrefix = "tecno ki5", maxFps = 14),
                DeviceQuirk(modelPrefix = "TECNO", maxFps = 20, maxRung = 1),
            )
        val caps = s.tightenedBy(quirks, tecno)
        assertEquals(14, caps.maxFps)
        assertEquals(1, caps.bestRung)
    }
}
