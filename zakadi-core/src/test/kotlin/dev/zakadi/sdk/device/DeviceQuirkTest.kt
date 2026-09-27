@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceQuirkTest {
    private val device = DeviceIdentity("TECNO", "KI5q", 10)

    @Test
    fun theModelPrefixStartsManufacturerSpaceModel() {
        assertEquals("TECNO KI5q", device.modelName)
        assertTrue(DeviceQuirk(modelPrefix = "TECNO KI5").matches(device))
        assertTrue(DeviceQuirk(modelPrefix = "tecno ki5").matches(device))
        assertFalse(DeviceQuirk(modelPrefix = "KI5").matches(device))
        assertFalse(DeviceQuirk(modelPrefix = "Infinix").matches(device))
    }

    @Test
    fun thePlatformMustBeAndroid() {
        assertTrue(DeviceQuirk(platform = "android").matches(device))
        assertTrue(DeviceQuirk().matches(device))
        assertFalse(DeviceQuirk(platform = "web").matches(device))
        assertFalse(DeviceQuirk(platform = "ios").matches(device))
    }

    @Test
    fun theOsMajorVersionMustLieInTheRange() {
        assertTrue(DeviceQuirk(osMajorMin = 9, osMajorMax = 11).matches(device))
        assertTrue(DeviceQuirk(osMajorMin = 10, osMajorMax = 10).matches(device))
        assertFalse(DeviceQuirk(osMajorMin = 11).matches(device))
        assertFalse(DeviceQuirk(osMajorMax = 9).matches(device))
    }

    @Test
    fun aBrowserKeyMatchesNoNativeDevice() {
        assertFalse(DeviceQuirk(platform = "android", browser = "Chrome").matches(device))
        assertFalse(DeviceQuirk(browserMajorMin = 1).matches(device))
        assertFalse(DeviceQuirk(browserMajorMax = 200).matches(device))
    }

    @Test
    fun everyKeyPresentMustMatch() {
        val quirk = DeviceQuirk(platform = "android", modelPrefix = "TECNO", osMajorMin = 11)
        assertFalse(quirk.matches(device))
        assertTrue(quirk.matches(device.copy(osMajor = 11)))
    }

    @Test
    fun theOsMajorVersionOfAReleaseString() {
        assertEquals(8, DeviceIdentity.osMajor("8.1.0"))
        assertEquals(14, DeviceIdentity.osMajor("14"))
        assertEquals(0, DeviceIdentity.osMajor("Baklava"))
        assertEquals(0, DeviceIdentity.osMajor(""))
    }
}
