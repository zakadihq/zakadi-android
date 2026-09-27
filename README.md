# zakadi-android

Android SDK for Zakadi. Gradle modules `:zakadi-core`, `:zakadi-ui-compose`, `:zakadi-ui-views`, `:zakadi-protocol`; package `dev.zakadi.sdk`; Maven group `dev.zakadi`. Specification: `zakadi/spec/07-native-mobile-sdk.md` Part B and the SDK contract `spec/05-sdk-contract.md`.

Status: pre-release, no public API yet. `:zakadi-protocol` (pure Kotlin/JVM) holds the media framing codec and the `attest` hash chain, graded by the `zakadi-protocol` `v0.1.0` conformance vectors; `:zakadi-core` (minSdk 26, compileSdk 37) holds the internal capture pipeline, AVC encoder and capability probe of spec 07 7.18, 7.19 and 7.26, carries the instrumented tests and the release AAR, and keeps the encoder probe of phase 0 in its test fixtures and instrumented tests. The other modules arrive with their tickets; the specification lives in the `zakadi` repository.

## Build

JDK 17 and the Android SDK (`ANDROID_HOME`); Gradle comes from the wrapper. Install the git hooks once per clone with `lefthook install`: they run the commands of `.github/workflows/ci.yml`.

```sh
./gradlew spotlessCheck         # format: ktfmt, kotlinlang style (spotlessApply rewrites)
./gradlew lint                  # Android lint on both modules
./gradlew test                  # unit tests: the framing and chain vectors on the JVM
./gradlew connectedAndroidTest  # instrumented tests: the same vectors on a device, API 26 or later
```

The vectors come only from the `zakadi-protocol` `v0.1.0` tag archive: the `protocolVectors` task downloads it, checks its SHA-256 and extracts `vectors/` under `zakadi-protocol/build/`.

## Encoder probe (phase 0, measurement 6)

The probe of hardware H.264 encoder behaviour on the target phones (spec 09 9.11 item 6, D105): the capture pipeline runs schedule 1 on the front camera, five rungs of the ladder and a bitrate step run, about three minutes, and writes one log in format 1 per invocation, `probe-<unix ms>.jsonl`, which the ML stream converts. It ships in the instrumented test APK, never in the AAR, and runs only when asked.

1. Get `zakadi-core-debug-androidTest.apk`: build it with `./gradlew :zakadi-core:assembleDebugAndroidTest` (it lands in `zakadi-core/build/outputs/apk/androidTest/debug/`), or download the `encoder-probe` artifact of `ci.yml`'s `integration` job on `main`.
2. Install it, with the camera permission granted:

   ```sh
   adb install -r -t -g zakadi-core-debug-androidTest.apk
   ```

3. Run schedule 1; the output names the log:

   ```sh
   adb shell am instrument -w -e zakadi.probe run -e class dev.zakadi.sdk.probe.EncoderProbeRun dev.zakadi.sdk.test/androidx.test.runner.AndroidJUnitRunner
   ```

   Add `-e zakadi.probe.encoder software` for the software path of spec 07 7.19, or `-e zakadi.probe.path B` for capture path B of 7.18.
4. Pull the logs:

   ```sh
   adb pull /sdcard/Android/data/dev.zakadi.sdk.test/files/zakadi-probe/
   ```

An APK built on another machine replaces an installed one only after `adb uninstall dev.zakadi.sdk.test`, which deletes the logs not yet pulled: pull them first.

## Licence

Zakadi SDKs and client libraries are open source under the Apache License 2.0 (see `LICENSE`; the `NOTICE` file reserves the Zakadi trademarks). They are clients for the Zakadi service, which is proprietary; using it requires an account and acceptance of the Zakadi Terms of Service. Zakadi and the Zakadi logo are trademarks and are not covered by the Apache licence.
