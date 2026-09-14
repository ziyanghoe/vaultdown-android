# Version 0.2.0 validation status

- 29 production core tests passed locally, including OAuth polling intervals, slowdown backoff, expiry, denial, and terminal errors.
- Android compilation and lint for this update are pending CI.
- OAuth app registration / Client ID configuration and real account authorization are not complete. No live OAuth login, repository listing, refresh, or device UI test is claimed.
- Real-device checks: browser authorization, rotation while waiting, close/cancel, denied/expired codes, >100 repositories and branches, organization access, empty repositories, revoked sessions, and reconnecting existing local notes.
- Version 0.1.0 previously passed Android compilation and lint. This is not evidence that the new OAuth integration works end to end.
- Debug APK signing is ephemeral in CI. Export unsynced notes before uninstalling an older build. A persistent signing key is still needed for dependable in-place upgrades.
