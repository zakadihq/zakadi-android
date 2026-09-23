package dev.zakadi.sdk

import java.io.File

/** The `vectors/<kind>` directory that the protocolVectors task extracted under build/. */
fun vectorDir(kind: String): File {
    val root =
        checkNotNull(System.getProperty("zakadi.vectors")) {
            "zakadi.vectors is unset; run the tests through Gradle"
        }
    return File(root, kind)
}

/** The names of the `*.json` cases in `vectors/<kind>`, failing when there are none. */
fun vectorCases(kind: String): List<String> {
    val names =
        vectorDir(kind)
            .listFiles { f -> f.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .sorted()
    check(names.isNotEmpty()) { "no vectors/$kind/*.json case under ${vectorDir(kind)}" }
    return names
}
