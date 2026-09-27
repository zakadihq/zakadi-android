@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Test

class ProbeCacheTest {
    private val report =
        ProbeReport(
            fingerprint = "TECNO/KI5q/KI5q:11/RP1A.200720.011/1:user/release-keys",
            sdkInt = 30,
            probeVersion = CapabilityProbe.VERSION,
            checks =
                listOf(
                    ProbeCheck(
                        CapabilityProbe.AVC_ENCODER,
                        CheckOutcome.PASSED,
                        "OMX.MTK.VIDEO.ENCODER.AVC step 0",
                        92_000_000,
                    ),
                    ProbeCheck(CapabilityProbe.OPUS, CheckOutcome.NOT_RUN, "waits for 7.20"),
                ),
            facts =
                DeviceFacts(
                    frontCamera = true,
                    avcEncoder = true,
                    lowRamDevice = true,
                    totalMemBytes = 1_950_000_000,
                    legacyCamera = false,
                    eglRecordable = true,
                ),
        )

    @Test
    fun aReportSurvivesTheCache() {
        assertEquals(report, ProbeCache.decode(ProbeCache.encode(report)))
    }

    @Test
    fun theKeyIsTheBuildAndTheProbeVersion() {
        assertEquals("fp|30|1", ProbeCache.key("fp", 30, 1))
    }
}
