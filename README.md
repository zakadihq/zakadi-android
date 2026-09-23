# zakadi-android

Android SDK for Zakadi. Gradle modules `:zakadi-core`, `:zakadi-ui-compose`, `:zakadi-ui-views`, `:zakadi-protocol`; package `dev.zakadi.sdk`; Maven group `dev.zakadi`. Specification: `zakadi/spec/07-native-mobile-sdk.md` Part B and the SDK contract `spec/05-sdk-contract.md`.

Status: scaffold. `:zakadi-protocol` (pure Kotlin/JVM) holds the media framing codec and the `attest` hash chain, graded by the `zakadi-protocol` `v0.1.0` conformance vectors; `:zakadi-core` is an empty Android library (minSdk 26, compileSdk 37) that carries the instrumented tests and the release AAR. The other modules arrive with their tickets; the specification lives in the `zakadi` repository.

## Build

JDK 17 and the Android SDK (`ANDROID_HOME`); Gradle comes from the wrapper. Install the git hooks once per clone with `lefthook install`: they run the commands of `.github/workflows/ci.yml`.

```sh
./gradlew spotlessCheck         # format: ktfmt, kotlinlang style (spotlessApply rewrites)
./gradlew lint                  # Android lint on both modules
./gradlew test                  # unit tests: the framing and chain vectors on the JVM
./gradlew connectedAndroidTest  # instrumented tests: the same vectors on a device, API 26 or later
```

The vectors come only from the `zakadi-protocol` `v0.1.0` tag archive: the `protocolVectors` task downloads it, checks its SHA-256 and extracts `vectors/` under `zakadi-protocol/build/`.

## Licence

Zakadi SDKs and client libraries are open source under the Apache License 2.0 (see `LICENSE`; the `NOTICE` file reserves the Zakadi trademarks). They are clients for the Zakadi service, which is proprietary; using it requires an account and acceptance of the Zakadi Terms of Service. Zakadi and the Zakadi logo are trademarks and are not covered by the Apache licence.
