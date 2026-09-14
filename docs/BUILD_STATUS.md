# Validation status

Prepared 2026-09-14.

## Completed locally

- 26 executable tests passed against the production Java sync core, including simulated interrupted/racing operations. Full output is in `test-results.txt`.
- UTF-8 Git blob SHA checked against the installed `git hash-object` implementation.
- Reviewed credential handling, complete-tree preconditions, revision checks, acknowledgement behavior, remote deletions, and queued keystroke handling.
- XML resources and manifest parsed for well-formedness; shell scripts syntax checked.

## Not verified

- Android/Kotlin compilation, dependency resolution, Android lint, rendering, and installation.
- Live GitHub pull/push using a user token.
- SQLite, Android Keystore, WorkManager, export picker, and lifecycle behavior on an emulator/device.
- GitHub Actions execution.

The private `ziyanghoe/vaultdown-android` repository and write access were verified before publication.

The local environment supplies Java 17 but no Android SDK or Gradle installation. Attempts to reach Android/Gradle dependency endpoints were blocked or timed out. No APK has been produced. The included workflow is the next compilation and lint gate once the project is on GitHub.

## Device validation before regular use

1. Compile and lint successfully; install on API 26 and a recent Android device/emulator. Check small portrait, landscape, tablet, large font, keyboard, and rotation behavior.
2. Create an initialized private test notes repo with Unicode filenames, nested folders, a table, and a task list. Connect a token limited to this repo.
3. Confirm the initial tree and previews. Create a nested note, edit it, and verify the new commit/content on GitHub.
4. Disable networking; edit multiple notes. Reopen the app and verify local persistence. Reconnect and confirm queued uploads.
5. Edit the same note remotely and locally. Verify both texts survive and test every conflict choice, including a remote deletion.
6. Type while a slow upload/download is running. Confirm the newest draft survives and any changed remote base is reviewed.
7. Kill and restart the process after **Saved locally**. Confirm notes persist. Check unsynced edits remain associated with the correct repo/branch after reconnecting.
8. Test expired/revoked tokens, read-only token, protected branch, unavailable branch, rate limits, and lost connectivity. Ensure failures leave local content intact.
9. Validate actual periodic execution under Android battery scheduling. Confirm manual and resume sync still work.
10. Export a note with Android's file picker and compare bytes. Check that the connection dialog cannot be captured in screenshots and that the token is absent from saved state and logs.
11. Set up a persistent signing key before depending on updates to preserve installed app data. Keep the key in an approved secret store, never in source control.
