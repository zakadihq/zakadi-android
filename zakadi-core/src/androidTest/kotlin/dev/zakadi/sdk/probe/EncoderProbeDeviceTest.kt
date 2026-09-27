@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import android.os.Build
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.EncoderPreference
import dev.zakadi.sdk.capture.startCameraActivity
import dev.zakadi.sdk.device.DeviceIdentity
import dev.zakadi.sdk.encode.pickAvcEncoder
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The encoder probe on the device (CI: the emulator's software encoder and emulated front camera),
 * with schedule 1 shortened ten times: a log that parses in full, with one `run` and one `run_end`
 * per run, the device's facts and no media (spec 09 section 9.11 item 6, D45, 5.1, 5.13).
 */
class EncoderProbeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "zakadi-probe-test")

    @Before
    fun clear() {
        directory.deleteRecursively()
    }

    @After
    fun remove() {
        directory.deleteRecursively()
    }

    @Test
    fun theShortenedScheduleWritesALogThatParsesInFull() {
        val encoder = checkNotNull(pickAvcEncoder(preferSoftware = true)) { "no software encoder" }
        val args = ProbeArgs(EncoderPreference.SOFTWARE, CapturePath.A, divisor = 10)
        val activity = startCameraActivity()
        val log =
            try {
                AndroidProbe.run(context, args, ProbeSchedule.ONE.shortened(10), directory)
            } finally {
                activity.finish()
            }
        val text = log.readText()
        val lines = LogReader.read(text)

        val device = lines.first()
        assertEquals("probe-${device.long("wall_ms")}.jsonl", log.name)
        assertEquals("android", device.str("platform"))
        assertEquals(DeviceIdentity.current().modelName, device.str("phone"))
        assertEquals(Build.FINGERPRINT, device.str("os_build"))
        assertEquals(1, device.int("schedule"))
        val logged = device.getValue("args").jsonObject
        assertEquals("software", logged.str("encoder"))
        assertEquals(10, logged.int("divisor"))
        assertTrue(device.str("tier") in setOf("U", "L", "S"))
        assertTrue("front camera", device.getValue("front_camera").jsonPrimitive.boolean)
        assertNotNull("the camera level", device.str("camera_level"))
        val encoders = (device.getValue("encoders") as JsonArray).map { it.jsonObject }
        val listed = encoders.single { it.str("name") == encoder.name }
        assertFalse(listed.getValue("hw").jsonPrimitive.boolean)
        assertEquals(
            (0..4).toList(),
            (listed.getValue("rungs") as JsonArray).map { it.jsonPrimitive.int },
        )

        val runs = lines.filter { it.str("kind") == "run" }
        assertEquals(listOf(0, 1, 2, 3, 4, 2), runs.map { it.int("rung") })
        for (run in runs) {
            assertEquals(encoder.name, run.str("encoder"))
            assertFalse(run.getValue("hw").jsonPrimitive.boolean)
            assertEquals("A", run.str("path"))
            val index = run.int("run")
            val outs = lines.filter { it.str("kind") == "out" && it.int("run") == index }
            assertTrue(
                "run $index has frames",
                lines.any { it.str("kind") == "in" && it.int("run") == index },
            )
            assertTrue(
                "run $index starts on an IDR",
                outs.first().getValue("key").jsonPrimitive.boolean,
            )
            val end = lines.single { it.str("kind") == "run_end" && it.int("run") == index }
            assertEquals("run $index", JsonNull, end.getValue("error"))
        }
        val end = lines.last()
        assertEquals(6, end.int("runs"))
        assertEquals("done", end.str("reason"))

        // No media and no identifier beyond the device facts named in log format 1.
        val androidId =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) assertFalse("the Android ID", text.contains(androidId))
        val strings =
            device.keys.filter { (device.getValue(it) as? JsonPrimitive)?.isString == true }
        assertEquals(
            listOf("kind", "platform", "phone", "soc", "os", "os_build", "tier", "camera_level") +
                listOfNotNull("thermal".takeIf { device.getValue(it) !is JsonNull }),
            strings,
        )
    }

    private fun JsonObject.str(key: String): String? =
        (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.content.toLong()
}
