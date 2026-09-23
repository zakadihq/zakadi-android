plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.licensee)
}

android {
    namespace = "dev.zakadi.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // Versions are pinned by spec 07 7.17 and moved by Dependabot, not by a lint warning.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

// The licence check of the scan step in ci.yml: the build fails on a licence not allowed here.
licensee { allow("Apache-2.0") }

val protocolVectors = configurations.dependencyScope("protocolVectors")
val protocolVectorFiles =
    configurations.resolvable("protocolVectorFiles") {
        extendsFrom(protocolVectors.get())
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("zakadi-protocol-vectors")) }
    }

dependencies {
    api(project(":zakadi-protocol"))
    androidTestImplementation(testFixtures(project(":zakadi-protocol")))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    protocolVectors(project(":zakadi-protocol"))
}

// The instrumented tests read the vectors of :zakadi-protocol from the test APK's assets.
val protocolVectorAssets =
    tasks.register<ProtocolVectorAssets>("protocolVectorAssets") {
        vectors.from(protocolVectorFiles)
    }

androidComponents {
    onVariants { variant ->
        variant.androidTest
            ?.sources
            ?.assets
            ?.addGeneratedSourceDirectory(protocolVectorAssets, ProtocolVectorAssets::assetsDir)
    }
}

/** Copies the framing and chain vectors that :zakadi-protocol extracted into [assetsDir]. */
abstract class ProtocolVectorAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val vectors: ConfigurableFileCollection

    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(vectors) { include("vectors/framing/**", "vectors/chain/**") }
            into(assetsDir)
        }
    }
}
