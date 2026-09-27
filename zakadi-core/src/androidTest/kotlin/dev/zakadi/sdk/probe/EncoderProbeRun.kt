@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.EncoderPreference
import dev.zakadi.sdk.capture.startCameraActivity
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod

/**
 * The encoder probe of phase 0 measurement 6 (spec 09 section 9.11 item 6, D105), as the field team
 * runs it: schedule 1 on the front camera in the camera test activity, one log per invocation,
 * whose path goes to the instrumentation output. It runs only when asked; otherwise it holds no
 * test, and the other instrumented tests run as before:
 * ```
 * adb shell am instrument -w -e zakadi.probe run \
 *     -e class dev.zakadi.sdk.probe.EncoderProbeRun \
 *     dev.zakadi.sdk.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * `-e zakadi.probe.encoder software` takes the software path of spec 07 section 7.19, and `-e
 * zakadi.probe.path B` capture path B of 7.18. The log lands in `files/zakadi-probe/` of the test
 * app's external storage.
 */
@RunWith(EncoderProbeRun.WhenAsked::class)
class EncoderProbeRun {
    /**
     * Gives the probe's test only when the instrumentation was given `-e zakadi.probe`, and none
     * otherwise: the connected test report counts a test skipped by an assumption, or ignored, as a
     * failure.
     */
    class WhenAsked(test: Class<*>) : BlockJUnit4ClassRunner(test) {
        override fun getChildren(): List<FrameworkMethod> =
            if (InstrumentationRegistry.getArguments().getString(PROBE) == null) emptyList()
            else super.getChildren()
    }

    @Test
    fun runScheduleOne() {
        val arguments = InstrumentationRegistry.getArguments()
        val probe = arguments.getString(PROBE)
        if (probe != "run") throw AssertionError("-e $PROBE takes run, not $probe")
        val encoders =
            mapOf(
                "hardware" to EncoderPreference.HARDWARE,
                "software" to EncoderPreference.SOFTWARE,
            )
        val args =
            ProbeArgs(
                encoder = option(arguments, ENCODER, encoders) ?: EncoderPreference.HARDWARE,
                path =
                    option(arguments, PATH, CapturePath.entries.associateBy { it.name })
                        ?: CapturePath.A,
            )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = startCameraActivity()
        val log =
            try {
                AndroidProbe.run(
                    context,
                    args,
                    ProbeSchedule.ONE,
                    AndroidProbe.logDirectory(context),
                )
            } finally {
                activity.finish()
            }
        val end = LogReader.read(log.readText()).last()
        val reason = end.getValue("reason").jsonPrimitive.content
        val runs = end.getValue("runs").jsonPrimitive.content
        report(instrumentation, "$PROBE: ${log.absolutePath} ($reason, $runs runs)\n")
    }

    /** The value of argument [name] among [values], or null when it is absent. */
    private fun <T> option(arguments: Bundle, name: String, values: Map<String, T>): T? {
        val value = arguments.getString(name) ?: return null
        return values[value]
            ?: throw AssertionError(
                "-e $name takes ${values.keys.joinToString(" or ")}, not $value"
            )
    }

    /** Prints [text] in the output of `am instrument -w`, outside the test's own status. */
    private fun report(instrumentation: Instrumentation, text: String) {
        instrumentation.sendStatus(
            STATUS_IN_PROGRESS,
            Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, text) },
        )
    }

    private companion object {
        const val PROBE = "zakadi.probe"
        const val ENCODER = "zakadi.probe.encoder"
        const val PATH = "zakadi.probe.path"

        /** A status neither the runner nor the tools count as a test's start or end. */
        const val STATUS_IN_PROGRESS = 2
    }
}
