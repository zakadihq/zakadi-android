# Changelog

All notable changes to this module are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- The `zakadi.v1` media message codec: `Header` encoding and decoding, the probe and audio
  batch payload parsers, and `FramingException` with the error codes of the framing vectors.
- The `attest` hash chain (`HashChain`) and the unverified reading of the client token
  (`tokenClaims`, `jtiBytes`).
- The `@InternalZakadiApi` opt-in that marks all of the above as internal SDK API.

[Unreleased]: https://github.com/zakadihq/zakadi-android/commits/main
