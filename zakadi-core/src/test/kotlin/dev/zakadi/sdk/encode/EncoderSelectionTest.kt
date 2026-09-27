@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderSelectionTest {
    private fun codec(
        name: String,
        hardware: Boolean = true,
        vendor: Boolean = hardware,
        alias: Boolean = false,
        encoder: Boolean = true,
        types: List<String> = listOf("video/avc"),
    ) =
        EncoderCandidate(
            name,
            encoder,
            types,
            softwareOnly = !hardware,
            hardwareAccelerated = hardware,
            alias = alias,
            vendor = vendor,
        )

    private fun pick(list: List<EncoderCandidate>, software: Boolean = false, sdk: Int = 30) =
        pickAvcEncoder(list, software, sdk)?.name

    @Test
    fun hardwareEncodersOnlyUnlessAsked() {
        val list =
            listOf(codec("c2.android.avc.encoder", hardware = false), codec("c2.qti.avc.encoder"))
        assertEquals("c2.qti.avc.encoder", pick(list))
        assertNull(pick(listOf(codec("c2.android.avc.encoder", hardware = false))))
    }

    @Test
    fun vendorCodecsComeFirstFromApi29() {
        val list = listOf(codec("c2.hw.avc.encoder", vendor = false), codec("c2.qti.avc.encoder"))
        assertEquals("c2.qti.avc.encoder", pick(list, sdk = 29))
        assertEquals("c2.hw.avc.encoder", pick(list, sdk = 28))
    }

    @Test
    fun theFlagsDecideFromApi29() {
        val alias = codec("OMX.qcom.video.encoder.avc", alias = true)
        val notAccelerated =
            EncoderCandidate(
                "c2.vendor.avc.encoder",
                isEncoder = true,
                mimeTypes = listOf("video/avc"),
                softwareOnly = false,
                hardwareAccelerated = false,
                vendor = true,
            )
        assertTrue(isSoftwareEncoder(alias, 29))
        assertTrue(isSoftwareEncoder(notAccelerated, 29))
        assertFalse(isSoftwareEncoder(codec("c2.qti.avc.encoder"), 29))
        assertNull(pick(listOf(alias, notAccelerated), sdk = 29))
    }

    @Test
    fun theNameDecidesBelowApi29AndTheFlagsAreIgnored() {
        val list =
            listOf(
                codec("OMX.google.h264.encoder", hardware = true),
                codec("OMX.MTK.VIDEO.ENCODER.AVC", hardware = false),
            )
        assertEquals("OMX.MTK.VIDEO.ENCODER.AVC", pick(list, sdk = 28))
        assertEquals("OMX.google.h264.encoder", pick(list, software = true, sdk = 28))
    }

    @Test
    fun theNameRuleOfD86() {
        for (name in
            listOf(
                "OMX.google.h264.encoder",
                "omx.GOOGLE.h264.encoder",
                "c2.android.avc.encoder",
                "c2.google.avc.encoder",
                "OMX.ffmpeg.h264.encoder",
                "c2.ffmpeg.h264.encoder",
                "OMX.hisi.sw.avc.encoder",
                "AVCEncoder",
                "com.example.h264.encoder",
            )) {
            assertTrue(name, isSoftwareByName(name))
        }
        for (name in
            listOf(
                "OMX.qcom.video.encoder.avc",
                "OMX.MTK.VIDEO.ENCODER.AVC",
                "OMX.Exynos.AVC.Encoder",
                "c2.exynos.h264.encoder",
                "c2.mtk.avc.encoder",
                "OMX.IMG.TOPAZ.VIDEO.Encoder",
            )) {
            assertFalse(name, isSoftwareByName(name))
        }
    }

    @Test
    fun softwareOnlyWhenAskedAndThePlatformsOwnFirst() {
        val list =
            listOf(
                codec("c2.vendor.avc.encoder.sw", hardware = false, vendor = true),
                codec("OMX.google.h264.encoder", hardware = false, vendor = false, alias = true),
                codec("c2.android.avc.encoder", hardware = false, vendor = false),
                codec("c2.qti.avc.encoder"),
            )
        assertEquals("c2.qti.avc.encoder", pick(list))
        assertEquals("c2.android.avc.encoder", pick(list, software = true))
        val old = listOf(codec("OMX.ffmpeg.h264.encoder"), codec("OMX.google.h264.encoder"))
        assertEquals("OMX.google.h264.encoder", pick(old, software = true, sdk = 26))
    }

    @Test
    fun onlyAvcEncodersCount() {
        val list =
            listOf(
                codec("c2.qti.avc.decoder", encoder = false),
                codec("c2.qti.hevc.encoder", types = listOf("video/hevc")),
                codec("c2.qti.avc.encoder", types = listOf("VIDEO/AVC")),
            )
        assertEquals("c2.qti.avc.encoder", pick(list))
    }

    @Test
    fun thePlatformOrderBreaksTies() {
        val list = listOf(codec("c2.first.avc.encoder"), codec("c2.second.avc.encoder"))
        assertEquals("c2.first.avc.encoder", pick(list))
        assertEquals("c2.first.avc.encoder", pick(list, sdk = 26))
    }
}
