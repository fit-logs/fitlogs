# Android CI and stable update releases

The active repository is `fit-logs/fitlogs`. Android CI runs unit tests, lint, an unsigned release build, catalog validation and release-tool tests. Signing is available only to the release workflow on `main`.

## Automatic publication

1. Change `VERSION_NAME` and increase `VERSION_CODE` together in `android/version.properties`.
2. Use a stable three-part version, e.g. `1.1.2`. The updater ignores pre-releases.
3. Commit matching notes in `android/docs/releases/v1.1.2.md`.
4. Merge into `main`. **Publish Android release** selects the version change and publishes after its checks pass.

The selector compares against the commit before the whole push, including multi-commit pushes. Ordinary Gradle edits with unchanged version values skip publication. Partial version changes, a non-increasing code or missing notes fail before signing. A new repository's initial import skips automatic publication; trigger its first release manually.

For a retry, use **Actions → Publish Android release → Run workflow**, select `main`, and optionally specify its exact tag. Feature-branch invocations cannot access signing. An already published release or a conflicting tag is refused.

## One-time signing setup

Android updates retain the original application ID and signing key. The expected certificate SHA-256 is:

`7ab64af6c9ab261ba03a9471b6a3adc59fa33d684d5d1c4ef1f06a6bc693ddce`

Configure these repository Actions secrets in `fit-logs/fitlogs`:

| Secret | Value |
| --- | --- |
| `FITNOTES_KEYSTORE_BASE64` | Original keystore, Base64 encoded |
| `FITNOTES_STORE_PASSWORD` | Original store password |
| `FITNOTES_KEY_PASSWORD` | Original key password |
| `FITNOTES_KEY_ALIAS` | Original alias |

Verify the certificate locally before uploading. To upload without saving or displaying the encoded key:

```sh
base64 -i "$HOME/.android/debug.keystore" | tr -d '\n' | \
  gh secret set FITNOTES_KEYSTORE_BASE64 --repo fit-logs/fitlogs
```

Upload the other values through the secret editor or `gh secret set`. Never commit keys or passwords. Preserve a private backup of the original keystore.

## Published update format

The workflow signs a non-debuggable release APK and verifies its certificate, package, version, minimum SDK and alignment. It prepares:

- `fitnotes.apk`: fixed name required by the updater.
- `fitnotes-update.json`: schema 1, package, version, size, minimum SDK and SHA-256, plus corresponding-source metadata.
- `fitlogs-source.tar.gz`: matching committed application, catalog, build scripts and licence notices.
- `SHA256SUMS`: APK, manifest and source checksums.

All files are uploaded to a draft. Every uploaded size and GitHub SHA-256 digest must match locally before publication. The release is then published as stable and latest. The publisher verifies `/releases/latest` resolves to that release. Failed asset checks leave the release as a draft.

`android/scripts/package-release.sh` prepares the signed bundle. `python3 android/scripts/publish-update.py` provides the same local preparation; add `--publish` to upload after review. Source must be committed so the source archive and tagged APK correspond. Builds, private exports, credentials and signing keys are excluded.

The first migration release needs one manual APK installation over the previous app. See [migration notes](fitlogs-migration.md). Future downloads can use **Settings → Update app**; Android still requires installation confirmation.
