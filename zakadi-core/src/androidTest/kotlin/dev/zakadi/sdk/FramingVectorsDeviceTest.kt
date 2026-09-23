package dev.zakadi.sdk

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The framing vectors on a device, down to minSdk 26 (spec 05 5.15). */
@RunWith(Parameterized::class)
class FramingVectorsDeviceTest(private val name: String) {
    @Test
    fun framingVector() {
        val json = checkNotNull(vectorFile("framing", "$name.json")).decodeToString()
        checkFramingVector(json, vectorFile("framing", "$name.bin"))
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = vectorCases("framing")
    }
}
