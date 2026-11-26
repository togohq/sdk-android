# Changelog

Every notable change to the Togo AI Android SDK, `ai.togohq:sdk-android`. The format is
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the library follows
[Semantic Versioning](https://semver.org/): each version is published to
`https://maven.togohq.ai` and tagged in this repository.

## [1.1.0] - 2026-11-26

Version 1.1.0 introduces TogoWrapper for SDK customization and improves error handling for wrapper
SDKs.

### Added

- **`TogoWrapper.set()`** allows a wrapper SDK to set its name and version for every event,
  enhancing SDK customization.

### Changed

- The documentation now includes **`TogoWrapper`** as part of the SDK's public surface, detailing
  its usage and limitations.

### Fixed

- Improved error handling in **`SdkIdentity.wrapper`** to refuse unknown wrapper names and null
  values with a log line.

## [1.0.0] - 2026-11-24

The first release of the Android SDK introduces core features for app lifecycle management and event
tracking.

### Added

- **`Togo.setup()`** initializes the SDK with configuration options, setting up the app for event
  tracking and lifecycle management.
- **`track()`** allows tracking of custom events, storing them in a durable queue for reliable
  delivery.
- **`identify()`** and **`setPersonProperties()`** manage user identity and properties, supporting
  personalized user experiences.
- **`setConsent()`** and **`getConsent()`** manage user consent for data collection, ensuring
  compliance with privacy regulations.
- **`flush()`** sends queued events to the server, ensuring data is transmitted efficiently.
- The SDK manages app lifecycle events, automatically tracking app installs, updates, and session
  starts.
- A durable queue stores events on disk, ensuring data persistence across app restarts.
- The SDK supports a 256 KiB event size limit and an 8 MiB queue size, managing data efficiently.
- The SDK is compatible with Android 5.0 (API 21) and later, supporting a wide range of devices.
- Semantic versioning is followed from this release onward, ensuring predictable versioning.

### Changed

- **Semantic versioning.** The public API is now stable: from this version on, a breaking change
  ships only in a new major version.
