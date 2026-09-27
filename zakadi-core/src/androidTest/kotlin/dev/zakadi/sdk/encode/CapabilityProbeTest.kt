@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import androidx.test.platform.app.InstrumentationRegistry
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.device.CapabilityProbe
import dev.zakadi.sdk.device.CheckOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The capability probe of spec 07 section 7.26 on the device: every check, the encoder's timed. */
class CapabilityProbeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyCheckIsReported() {
        val report = CapabilityProbe(context).run()
        assertEquals(
            listOf(
                CapabilityProbe.AVC_ENCODER,
                CapabilityProbe.CBR,
                CapabilityProbe.CONSTRAINED_BASELINE,
                CapabilityProbe.OPUS,
                CapabilityProbe.LOW_RAM,
                CapabilityProbe.TOTAL_MEM,
                CapabilityProbe.CAMERA_LEVEL,
                CapabilityProbe.EGL_RECORDABLE,
                CapabilityProbe.FRONT_CAMERA,
            ),
            report.checks.map { it.name },
        )
        assertEquals(
            CheckOutcome.NOT_RUN,
            report.checks.first { it.name == CapabilityProbe.OPUS }.outcome,
        )
        val encoder = report.checks.first()
        assertEquals(encoder.outcome == CheckOutcome.PASSED, report.facts.avcEncoder)
        if (pickAvcEncoder(preferSoftware = false) == null) {
            assertEquals(CheckOutcome.FAILED, encoder.outcome)
            assertEquals(CheckOutcome.NOT_RUN, report.checks[1].outcome)
        } else {
            assertTrue("timed: ${encoder.durationNanos}", (encoder.durationNanos ?: 0) > 0)
        }
    }

    @Test
    fun theEncoderCheckIsTimed() {
        val info = checkNotNull(pickAvcEncoder(false) ?: pickAvcEncoder(true))
        val check = CapabilityProbe(context).checkEncoder(info)
        assertEquals(check.detail, CheckOutcome.PASSED, check.outcome)
        assertTrue("timed: ${check.durationNanos}", (check.durationNanos ?: 0) > 0)
    }

    @Test
    fun theReportIsCachedForThisBuild() {
        val probe = CapabilityProbe(context)
        val first = probe.cachedOrRun()
        assertEquals(first, probe.cachedOrRun())
    }
}
