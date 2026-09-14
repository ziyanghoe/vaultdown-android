# Version 0.2.1 validation status

- 29 production core tests passed locally, including OAuth polling intervals, slowdown backoff, expiry, denial, and terminal errors.
- Android compilation, lint, and all 29 core tests passed in [run 34813784172](https://github.com/ziyanghoe/vaultdown-android/actions/runs/34813784172), commit `02583b9b926323e6d5e8c2388b8f3bb524c019e2`. A debug APK was uploaded. That 0.2.0 build did not contain a Client ID. The 0.2.1 update configures the supplied public Client ID. Compilation, lint, and all 29 core tests passed in [run 34827990777](https://github.com/ziyanghoe/vaultdown-android/actions/runs/34827990777), commit `00c323a2b8966d1bc93897d8eb4a74145e0fce5d`.
- The OAuth Client ID is configured. Device-flow enablement and real account authorization remain unverified. No live OAuth login, repository listing, refresh, or device UI test is claimed.
- Real-device checks: browser authorization, rotation while waiting, close/cancel, denied/expired codes, >100 repositories and branches, organization access, empty repositories, revoked sessions, and reconnecting existing local notes.
- Version 0.1.0 previously passed Android compilation and lint. This is not evidence that the new OAuth integration works end to end.
- Debug APK signing is ephemeral in CI. Export unsynced notes before uninstalling an older build. A persistent signing key is still needed for dependable in-place upgrades.
