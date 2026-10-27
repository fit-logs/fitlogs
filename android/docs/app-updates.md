# GitHub app updates

Use **Settings → Update app**. Opening this screen checks the latest stable
release in `avivajpeyi/fitnotes`. It shows the installed version, update notes,
download progress, retry controls and an explicit Install update button.

Install this first updater APK once from the GitHub release link. Future updates
can be downloaded in the app. Android 8+ requires **Allow FitNotes to install
unknown apps** once; return to FitNotes afterward and tap Install update. Android
always presents its own install confirmation. No uninstall or data clearing is
used. System policy can still block installation.

Updates use the existing package and signing key. The app checks GitHub HTTPS
release/asset URLs, bounded metadata/file sizes, SHA-256, APK package name, strictly
newer version code and the exact installed signing certificate. It checks the APK
again before handing a read-only content URI to Android. Drafts and prereleases
are ignored. The checksum is required in the release manifest; GitHub asset
checksums are also compared when supplied.

Requests happen only from this screen. Only public release information and the
APK are fetched. No training records, photos, preferences, clipboard text or
GitHub credentials are sent. Downloads stay in private cache; interrupted files
are discarded and cannot be installed. Rotation retains a running request;
a process restart requires checking/downloading again. Network reads have bounded
timeouts. Android installation remains separate from all workout database code.

## Publishing the next update

Keep the original local Android signing key. It must never be committed, attached
to a release, or replaced with a new machine's automatically generated debug key.

1. Increase `VERSION_CODE` and `VERSION_NAME` in `android/version.properties`.
2. Add the change notes to a local text file.
3. Run `python3 android/scripts/publish-update.py --notes-file /path/to/notes.txt`
   from the repository. This builds/tests/lints and prepares an ignored bundle at
   `android/build/update-release/android-v<name>-<code>/` without uploading it.
4. Review that bundle, then add `--publish` to upload it. The script creates a
   draft, verifies every uploaded SHA-256/size, and publishes it as the latest
   stable release only after verification. Existing release tags are rejected.

The bundle contains `fitnotes.apk`, `fitnotes-update.json`, `SHA256SUMS` and a
matching `fitnotes-source.tar.gz` snapshot, including the Android module's licence
and third-party notices. The source archive uses an explicit source/build-file
allowlist. It excludes databases, captures, photos, exports, local configuration,
credentials, caches and signing keys. Pending source edits are included without
changing the checkout or Git index. The source snapshot, rather than GitHub's
automatic base-tag ZIP, corresponds to this APK.

Manifest v1 fields: `schemaVersion`, `packageName`, `versionCode`, `versionName`,
`minSdk`, `apkName`, `size`, `sha256`. The publisher also records source-archive
name/checksum. Stable release assets must be named `fitnotes.apk` and
`fitnotes-update.json`. Newer version codes control availability; a higher display
version alone does not count as an update.

Android reference: [distribution and unknown-app opt-in](https://developer.android.com/studio/publish#publishing-unknown).
GitHub reference: [Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release).
