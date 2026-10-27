# Automated Android CI and beta releases

## Workflows

- `.github/workflows/android-ci.yml` runs on Android/catalog PRs and pushes to `main`. It installs JDK 25 and Android SDK 37, runs tests and lint, and assembles an **unsigned** APK. CI never publishes an APK as an app update.
- `.github/workflows/android-release.yml` is manually triggered on `main` from **Actions → Publish Android beta → Run workflow**. Provide a tag that exactly matches the Android Gradle `versionName`, e.g. `v1.0.0-beta.2`. It checks the version/tag and release notes, tests/builds, signs using the original certificate, verifies the APK/checksum, uploads the APK and `SHA256SUMS`, then publishes the pre-release. Existing draft releases (including beta.2) are supported; a published release or tag pointing elsewhere is never replaced.

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
  gh secret set FITNOTES_KEYSTORE_BASE64 --repo avivajpeyi/fitnotes
```

Set the other secrets through the GitHub secret editor or `gh secret set`. Do **not** put the keystore, passwords, or Base64 value in Git, an issue, screenshots, logs or chat. Preserve a private backup of the original key. A replacement key will **not** be able to update the installed app.

## Publishing beta.2

1. In GitHub, go to **Actions → Publish Android beta → Run workflow**.
2. Select branch `main` and enter `v1.0.0-beta.2` as the tag.
3. On a successful run, GitHub Releases will include the signed `fitnotes-1.0.0-beta.2.apk` and `SHA256SUMS`. The existing draft is retargeted to the verified `main` commit and published only after signing and asset verification.

Back up workout history (and photos if needed) before installing the APK over the existing FitNotes app. **Never uninstall** to work around a signature mismatch.

For the manual Mac build alternative, see [Android beta release](android-release.md) and `android/scripts/package-release.sh`.
