# Automated Android CI and beta releases

## Workflows

- `.github/workflows/android-ci.yml` runs on Android/catalog PRs and pushes to `main`. It installs JDK 25 and Android SDK 37, runs tests and lint, and assembles an **unsigned** APK. CI never publishes an APK as an app update.
- `.github/workflows/android-release.yml` automatically publishes a signed beta when a push to `main` changes Android `versionName` and increases `versionCode`. It checks the version/tag and release notes, tests/builds, signs using the original certificate, verifies the APK/checksum, uploads the APK and `SHA256SUMS`, then publishes the pre-release. Existing draft releases (including beta.2) are supported; a published release or tag pointing elsewhere is never replaced.

## Publishing future versions automatically

In the same PR:

1. Change `versionName` and increase `versionCode` in `android/app/build.gradle.kts`, for example `1.0.0-beta.3` and `4` after beta.2.
2. Add `android/docs/releases/v1.0.0-beta.3.md` with the release notes matching that version.
3. Merge into `main`. The release workflow selects that version and publishes after its tests, lint, build, signing and APK checks pass.

The selector compares the version before the entire push with the version at its final commit, including multi-commit pushes and merge commits. A Gradle dependency edit with unchanged version values skips publication. A version-name-only change, unchanged/decreased version code, or missing release notes fails before signing secrets are read. PRs never run the signing workflow. Merging this workflow change alone does not publish the existing beta.2 version.

For a failed run, use **Re-run failed jobs**, or **Actions → Publish Android beta → Run workflow** on `main`. Leave the optional tag empty to retry the current source version, or enter its exact tag. Manual runs from feature branches are skipped. Already-published releases are refused. Releases for the same tag are serialized; different new versions can finish independently.

These are GitHub pre-releases. Publication makes a signed APK available for download; it does not silently install updates on a phone.

The release action cannot publish until the original signing key is supplied as GitHub Actions repository secrets. The action refuses a missing or wrong key; it does not generate a new signing key or expose signing secrets to pull-request jobs.

## One-time signing setup on the original Mac

Android updates require the **same signing key** as the already-installed beta.1. Beta.1's certificate SHA-256 is:

`7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`

The original development key may be at `~/.android/debug.keystore`. Check the certificate before uploading anything:

```sh
keytool -list -v -keystore "$HOME/.android/debug.keystore" -alias androiddebugkey
```

In the repository's **Settings → Secrets and variables → Actions**, configure:

| Secret | Value |
| --- | --- |
| `FITNOTES_KEYSTORE_BASE64` | Original keystore file, Base64-encoded on one line |
| `FITNOTES_STORE_PASSWORD` | Original keystore password |
| `FITNOTES_KEY_PASSWORD` | Original key password |
| `FITNOTES_KEY_ALIAS` | Original key alias (often `androiddebugkey`) |

You can upload the keystore secret directly from the Mac using the authenticated GitHub CLI, without saving the Base64 text anywhere:

```sh
base64 -i "$HOME/.android/debug.keystore" | tr -d '\n' | \
  gh secret set FITNOTES_KEYSTORE_BASE64 --repo fit-logs/fitnotes
```

Set the other secrets through the GitHub secret editor or `gh secret set`. Do **not** put the keystore, passwords, or Base64 value in Git, an issue, screenshots, logs or chat. Preserve a private backup of the original key. A replacement key will **not** be able to update the installed app.

## Publishing beta.2

1. In GitHub, go to **Actions → Publish Android beta → Run workflow**.
2. Select branch `main` and enter `v1.0.0-beta.2` as the tag.
3. On a successful run, GitHub Releases will include the signed `fitnotes-1.0.0-beta.2.apk` and `SHA256SUMS`. The existing draft is retargeted to the verified `main` commit and published only after signing and asset verification.

Back up workout history (and photos if needed) before installing the APK over the existing FitNotes app. **Never uninstall** to work around a signature mismatch.

For the manual Mac build alternative, see [Android beta release](android-release.md) and `android/scripts/package-release.sh`.
