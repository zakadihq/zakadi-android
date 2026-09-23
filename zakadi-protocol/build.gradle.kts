import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `java-test-fixtures`
    alias(libs.plugins.android.lint)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.junit)
    testImplementation(libs.junit)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }

lint {
    abortOnError = true
    warningsAsErrors = true
    // Versions are pinned by spec 07 7.17 and moved by Dependabot, not by a lint warning.
    disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
}

// The conformance vectors of zakadi-protocol v0.1.0 (spec 00 0.11, spec 01 1.12), taken only
// from the tag archive and only when its SHA-256 is the recorded one.
val protocolVectors =
    tasks.register<ProtocolVectors>("protocolVectors") {
        url = "https://github.com/zakadihq/zakadi-protocol/archive/refs/tags/v0.1.0.tar.gz"
        sha256 = "de94fc3693659e0016d6eedb7e3b0e7fd688cc6e30247d387fb2eab4175759ba"
        archive = layout.buildDirectory.file("protocol/zakadi-protocol-v0.1.0.tar.gz")
        outputDir = layout.buildDirectory.dir("protocol/v0.1.0")
    }

tasks.test {
    inputs
        .dir(protocolVectors.flatMap { it.outputDir })
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("protocolVectors")
    systemProperty(
        "zakadi.vectors",
        layout.buildDirectory.dir("protocol/v0.1.0/vectors").get().asFile.path,
    )
}

// The extracted directory, for the instrumented tests of :zakadi-core.
configurations.consumable("protocolVectorElements") {
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("zakadi-protocol-vectors")) }
    outgoing.artifact(protocolVectors.flatMap { it.outputDir })
}

/** Downloads [url], checks its SHA-256 and extracts its `vectors/` directory into [outputDir]. */
abstract class ProtocolVectors : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:OutputFile abstract val archive: RegularFileProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun fetch() {
        val tarball = archive.get().asFile
        if (!tarball.isFile || sha256Of(tarball) != sha256.get()) {
            URI(url.get()).toURL().openStream().use { input ->
                tarball.outputStream().use { input.copyTo(it) }
            }
        }
        val actual = sha256Of(tarball)
        if (actual != sha256.get()) {
            tarball.delete()
            throw GradleException("${url.get()} has SHA-256 $actual, expected ${sha256.get()}")
        }
        files.sync {
            from(archives.tarTree(archives.gzip(tarball))) {
                include("*/vectors/**")
                eachFile {
                    relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
                }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
        for (kind in listOf("framing", "chain")) {
            val cases =
                outputDir.dir("vectors/$kind").get().asFile.listFiles { f -> f.extension == "json" }
            if (cases.isNullOrEmpty()) {
                throw GradleException("vectors/$kind of ${url.get()} holds no *.json case")
            }
        }
    }

    private fun sha256Of(file: java.io.File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
            "%02x".format(it)
        }
}
