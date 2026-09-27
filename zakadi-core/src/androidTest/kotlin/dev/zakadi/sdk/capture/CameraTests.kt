package dev.zakadi.sdk.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

private val instrumentation
    get() = InstrumentationRegistry.getInstrumentation()

/** Runs [command] in the device shell as the instrumentation and returns its output. */
fun shell(command: String): String =
    instrumentation.uiAutomation.executeShellCommand(command).use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }
    }

/**
 * Grants the camera permission, wakes the screen and starts [CameraTestActivity], which the caller
 * finishes.
 */
fun startCameraActivity(): Activity {
    val context = instrumentation.targetContext
    shell("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
    assertEquals(
        "the camera permission",
        PackageManager.PERMISSION_GRANTED,
        context.checkSelfPermission(Manifest.permission.CAMERA),
    )
    shell("input keyevent KEYCODE_WAKEUP")
    shell("wm dismiss-keyguard")
    val intent =
        Intent(context, CameraTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return instrumentation.startActivitySync(intent)
}

/** Waits up to [timeoutMs] for [condition], failing with [what] when it never holds. */
fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > end)
            fail("timed out after $timeoutMs ms waiting for $what")
        Thread.sleep(20)
    }
}
