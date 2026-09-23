package dev.zakadi.sdk

import androidx.test.platform.app.InstrumentationRegistry

private val assets
    get() = InstrumentationRegistry.getInstrumentation().context.assets

/**
 * The names of the `*.json` cases in the test APK's `vectors/<kind>`, failing when there are none.
 */
fun vectorCases(kind: String): List<String> {
    val names =
        assets
            .list("vectors/$kind")
            .orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()
    check(names.isNotEmpty()) { "no JSON case in the assets under vectors/$kind" }
    return names
}

/** The bytes of `vectors/<kind>/<file>`, or null when the test APK does not carry it. */
fun vectorFile(kind: String, file: String): ByteArray? {
    if (file !in assets.list("vectors/$kind").orEmpty()) return null
    return assets.open("vectors/$kind/$file").use { it.readBytes() }
}
