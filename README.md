# Vaultdown

A native Android Markdown notebook with an Obsidian-inspired file tree and automatic GitHub sync. Kotlin, Jetpack Compose, SQLite, WorkManager, and a separately testable Java sync core.

**Delivery status:** Published to the private `ziyanghoe/vaultdown-android` repository. Android compilation, lint, and 26 core tests passed. [Download the debug APK ZIP](https://github.com/ziyanghoe/vaultdown-android/actions/runs/34811961726/artifacts/10335205862), extract it, and install `app-debug.apk`. This artifact expires on 2026-09-28; later builds are under Actions. Device testing is still pending. See [BUILD_STATUS.md](docs/BUILD_STATUS.md).

## What it does

- Browse a collapsible, folder-first Markdown tree; search paths and filenames.
- Write offline with local autosave and preview headings, lists, code, quotes, tables, strikethrough, and task lists.
- Create notes in nested folders, for example `Projects/Cloud/Architecture.md`.
- Export the selected note as a real `.md` file using Android's file picker.
- Connect an existing GitHub repository and branch using your personal access token.
- Pull Markdown files and push local edits after approximately five seconds of inactivity, on app resume, and through background work scheduled every 15 minutes. Android controls actual background execution time.
- Keep independent local workspaces for each repository/branch, plus an offline workspace.
- Review simultaneous edits and choose **Keep my version**, **Use GitHub version**, or **Keep both**.
- Use a drawer on phones and a persistent sidebar on wider screens. Dark theme with violet and mint accents.

## Build an APK

### Publish the source to your private GitHub account

If you are using the downloadable source archive, extract it, open a terminal in `vaultdown-android`, and install [GitHub CLI](https://cli.github.com/). Then run:

```bash
gh auth login
bash scripts/publish-private.sh
```

The script uses the account authenticated with GitHub CLI, initializes a Git repository if needed, commits the source, creates `YOUR_ACCOUNT/vaultdown-android` with `--private`, pushes it, and verifies its privacy setting. Configure your Git commit name/email if Git asks. It stops if an `origin` remote or a repository with that name already exists. The initial `main` push starts the APK workflow below. If GitHub rejects workflow publishing, authorize workflow writes through GitHub CLI and retry the push to the created private remote.

### GitHub Actions

Once this project is pushed to your private source repository, open **Actions → Android checks and debug APK**. The workflow runs on pushes to `main` and can also be started manually. It installs the SDK, runs the core tests, compiles the app, runs Android lint, and uploads a `vaultdown-debug` artifact containing `app-debug.apk`.

This is a debug build for device testing, not a Play Store release. CI uses an ephemeral debug signing key; APKs from different runs may require uninstalling the previous build. Export any unsynced notes first because uninstalling deletes the local database. For ongoing use, configure a persistent signing key through GitHub Actions secrets; never commit it.

### Local Android development

Requirements: JDK 17, Gradle 8.9, Android SDK platform 35 and build tools 34.0.0. Android 8.0/API 26 or newer is supported.

1. Install the requirements; set `ANDROID_HOME` or add `sdk.dir=/absolute/path/to/Android/sdk` to an untracked `local.properties`.
2. Generate the official Gradle wrapper with `bash scripts/bootstrap-wrapper.sh`. This uses your installed Gradle, in a temporary standalone project, and copies the generated wrapper here. Wrapper binaries were not fabricated or bundled in this archive.
3. Open this directory in Android Studio and let Gradle sync finish.
4. Build with:

```bash
./gradlew :core:check :app:assembleDebug :app:lintDebug
```

Without generating a wrapper, an installed Gradle 8.9 can run the same tasks:

```bash
gradle :core:check :app:assembleDebug :app:lintDebug
```

The APK output is `app/build/outputs/apk/debug/app-debug.apk`. Install on an emulator/device from Android Studio or with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Connect your Markdown repository

The repository containing this app's source and the repository containing your Markdown notes are separate choices. The app does not assume the source repository is your notes repository.

1. Create or select a GitHub notes repository. Ensure the desired branch exists and contains at least one commit, such as a README. An empty initialized tree is supported; an unborn branch is not.
2. Create a **fine-grained personal access token**, grant access only to that notes repository, and enable **Contents: Read and write**. Organizations may require token approval/SSO. Branch protection must permit direct commits from your account.
3. In Vaultdown, open **Settings**. Enter the owner, repository name, branch (defaults to `main`), and token. Use names, not a URL.
4. Tap **Connect & sync**. The app validates the branch and read access, stores the token encrypted, opens that repository's workspace, and queues the initial sync. Write permission is exercised when you edit a note; a denied push leaves your edit local and shows an error.

Enter the token only in the app's connection form. It is not needed in source code, `local.properties`, CI, or this conversation. Updating a connection can retain the saved token by leaving the token field blank. Disconnect removes the stored token/config and background jobs; it keeps cached notes. Reconnect the same repository and branch to access that cache.

## Sync behavior

The app uses GitHub's REST API instead of cloning a Git working tree. It reads a complete recursive tree, downloads changed files by immutable blob SHA, and updates each dirty file with a SHA-guarded Contents API commit. An unchanged vault needs two metadata requests per pass; changed notes add requests. Large initial imports can take several background runs if Android interrupts work.

| Situation | Result |
| --- | --- |
| New file on GitHub | Download into the corresponding folder |
| Local-only edit | Upload with the previously synced SHA |
| Remote-only edit | Replace the unchanged cache |
| Same text already uploaded before an interrupted sync | Acknowledge it without another commit |
| Both versions changed | Preserve both and require conflict review |
| GitHub deletes an unchanged file | Remove the local cached row |
| GitHub deletes a locally edited file | Preserve local text as a deletion conflict |
| Typing overlaps network work | Optimistic row revisions preserve the draft; a new remote base triggers review |
| A tree response is truncated, inaccessible, or oversized | Abort the pass; never treat it as an empty repository |
| Network, permissions, or branch rules block a push | Retain the edit locally and display the error |

No force-push or unconditional overwrite is used. Keeping your version explicitly rebases it onto the reviewed remote content and queues a guarded upload. Keeping both creates a new local Markdown note and accepts the remote version at the original path. A further remote change is checked again.

## Security and current scope

- Token encryption uses AES-256-GCM and an Android Keystore key. The token field is masked, excluded from saved state, and protected against screenshots while open.
- Requests go only to `https://api.github.com`. Redirects are disabled. Credentials, request bodies, and server error bodies are not logged.
- Local notes are in app-private SQLite storage, **not a separately encrypted database**. Android backup/device-transfer exclusions are configured for both notes and credentials.
- Preview is rendered by native TextView/Markwon, without WebView, JavaScript, remote image fetching, or URL dispatch.
- Supported content: UTF-8 `.md` and `.markdown` files up to 1 MiB each, at most 2,000 supported notes per repository. Large recursive API responses are bounded; very large repositories fail explicitly.
- Hidden directories/files, symlinks, submodules, binary attachments, and Obsidian metadata are excluded. This version has no graph, plugins, backlink index, wiki-link navigation, image rendering, local delete/rename commands, multi-repository switcher, GitHub Enterprise support, or merge-by-line editor. Folders are derived from note paths.
- Use the initialized branch you intend to edit. Automatic sync creates one commit per changed note; repository-wide updates are not a single atomic commit.
- The status distinguishes local saving from remote syncing. Wait until **Saved locally** before closing immediately after a keystroke. Background scheduling depends on connectivity, battery settings, and Android; a force-stopped app must be reopened.

## Tests

The production sync engine and file tree have dependency-free Java integration tests:

```bash
bash scripts/test-core.sh
```

Or run `gradle :core:check` after setup. The shell harness invokes the JDK compiler module, so it also works where Java 17 is present but the `javac` launcher is not on PATH. Tests cover SHA compatibility with Git, UTF-8, path validation, pull/push, conflict/deletion handling, incomplete tree and network failures, resumed uploads, concurrent typing, cancellation, and tree ordering/search.

The tests use in-memory remote/store adapters and do not verify the live GitHub API, Android SQLite adapter, Compose rendering, WorkManager behavior, or Keystore on a device. Complete the [device validation checklist](docs/BUILD_STATUS.md) before relying on the app for important notes.

## Source map

- `core/src/main/java/dev/vaultdown/core/`: immutable notes, safe paths, sync decisions/engine, tree projection.
- `app/src/main/java/dev/vaultdown/app/VaultDatabase.kt`: SQLite optimistic updates, repository isolation, conflict resolution.
- `Settings.kt`: repository settings and encrypted token storage.
- `GitHubRemote.kt`: authenticated, bounded GitHub API access.
- `VaultdownApp.kt`: WorkManager scheduling and shared synchronization.
- `VaultViewModel.kt`: serialized editor saves and UI state.
- `VaultdownScreen.kt`: tree, editor, native Markdown preview, settings, conflict dialogs.

## Reference documentation

- [Android Gradle Plugin 8.7 compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes): AGP 8.7, Gradle 8.9, JDK 17, SDK 35.
- [GitHub Contents API](https://docs.github.com/en/rest/repos/contents#create-or-update-file-contents): token permissions and SHA-guarded updates.
- [Android work requests](https://developer.android.com/develop/background-work/background-tasks/persistent-work/getting-started/define-work): periodic scheduling constraints.
- [Markwon tables](https://noties.io/Markwon/docs/v4/ext-tables/) and [task lists](https://noties.io/Markwon/docs/v4/ext-tasklist/): native Markdown rendering.

Vaultdown is an independent project; it is not affiliated with Obsidian or GitHub.
