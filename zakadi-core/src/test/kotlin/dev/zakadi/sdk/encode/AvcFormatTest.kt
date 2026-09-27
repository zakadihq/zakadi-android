@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AvcFormatTest {
    private val surface = AvcFormatSpec(480, 640, 15, 400_000, 2000, surfaceInput = true)
    private val baseline =
        AvcCodecSupport(cbr = false, constrainedBaseline = false, maxBFramesKey = false)
    private val full = AvcCodecSupport(cbr = true, constrainedBaseline = true, maxBFramesKey = true)

    @Test
    fun cbrWhereListedElseVbr() {
        assertEquals(CBR, avcFormatKeys(surface, full, 0)["bitrate-mode"])
        assertEquals(VBR, avcFormatKeys(surface, baseline, 0)["bitrate-mode"])
    }

    @Test
    fun constrainedBaselineFromApi27WhereListedElseBaseline() {
        val listed = listOf(1, AVC_PROFILE_CONSTRAINED_BASELINE, 8)
        assertTrue(AvcCodecSupport.of(false, listed, 27).constrainedBaseline)
        assertFalse(AvcCodecSupport.of(false, listed, 26).constrainedBaseline)
        assertFalse(AvcCodecSupport.of(false, listOf(1, 8), 34).constrainedBaseline)
        assertEquals(0x10000, avcFormatKeys(surface, full, 0)["profile"])
        assertEquals(1, avcFormatKeys(surface, baseline, 0)["profile"])
    }

    @Test
    fun level31LatencyOneAndNoBFrames() {
        val keys = avcFormatKeys(surface, full, 0)
        assertEquals(0x200, keys["level"])
        assertEquals(1, keys["latency"])
        assertEquals(0, keys["priority"])
        assertEquals(0, keys["max-bframes"])
        assertTrue(AvcCodecSupport.of(true, emptyList(), 29).maxBFramesKey)
        assertFalse(AvcCodecSupport.of(true, emptyList(), 28).maxBFramesKey)
        assertFalse("max-bframes" in avcFormatKeys(surface, baseline, 0))
    }

    @Test
    fun rateBitrateAndGop() {
        val keys = avcFormatKeys(surface, full, 0)
        assertEquals(400_000, keys["bitrate"])
        assertEquals(15, keys["frame-rate"])
        assertEquals(2.0f, keys["i-frame-interval"])
    }

    @Test
    fun surfaceOrBufferInput() {
        assertEquals(0x7F000789, avcFormatKeys(surface, full, 0)["color-format"])
        val flexible = AvcFormatSpec(480, 640, 15, 400_000, 2000, surfaceInput = false)
        assertEquals(0x7F420888, avcFormatKeys(flexible, full, 0)["color-format"])
        val nv12 =
            AvcFormatSpec(480, 640, 15, 400_000, 2000, surfaceInput = false, colorFormat = 21)
        assertEquals(21, avcFormatKeys(nv12, full, 0)["color-format"])
    }

    @Test
    fun refusalsDropKeysInTheOrderOf719() {
        val all = avcFormatKeys(surface, full, 0).keys
        val dropped = (0..3).map { all - avcFormatKeys(surface, full, it).keys }
        assertEquals(
            listOf(
                emptySet(),
                setOf("latency"),
                setOf("latency", "profile", "level"),
                setOf("latency", "profile", "level", "bitrate-mode"),
            ),
            dropped,
        )
        assertEquals(4, FORMAT_REFUSAL_STEPS.size)
    }

    @Test
    fun bitrateRequestsClampToTheCodecRange() {
        val range = 64_000..12_000_000
        assertEquals(12_000_000, clampBitrate(50_000_000, range))
        assertEquals(64_000, clampBitrate(1_000, range))
        assertEquals(400_000, clampBitrate(400_000, range))
    }

    private companion object {
        const val CBR = 2
        const val VBR = 1
    }
}
