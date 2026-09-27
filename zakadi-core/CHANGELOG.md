# Changelog

All notable changes to this module are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The `dev.zakadi.sdk` Android library, with no public API yet: minSdk 26, compileSdk 37,
  and an `api` dependency on `zakadi-protocol`.
- The capture pipeline of spec 07 7.18 on the camera clock of 7.5 (paths A and B, the pacer,
  auto-exposure at the oval centre and +0.3 EV), the AVC encoder of 7.19 (encoder choice, the
  format and its refusal steps, Annex-B access units, keyframe and bitrate requests) and the
  device capability probe and tiers of 7.26, all `@InternalZakadiApi`, with a listener that
  reports every frame, output buffer, request and format.
- Dependencies on CameraX 1.6.2: `camera-core`, `camera-camera2`, `camera-lifecycle` and
  `camera-video`.

[Unreleased]: https://github.com/zakadihq/zakadi-android/commits/main
