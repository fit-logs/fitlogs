# Automatic Android backup

- Open **Settings → Backup and restore → Automatic backup** in FitNotes and
  enable **Use Android backup**. This opt-in is device-local and off by default.
- In Android Settings, search for **Backup**, enable Google's device backup,
  choose your account and set a screen lock. Android schedules the work,
  typically while idle on Wi-Fi after at least 24 hours. It stores the latest
  snapshot in device backup, rather than ordinary files visible in My Drive.
- Workouts, actual sets, imports, heart-rate samples, measurements, plans,
  targets, notes and training/ChatGPT preferences are included. Actual sets and
  planned targets remain separate. Timers are backed up paused.
- Photos, photo metadata, caches, repair exports, downloaded APKs and raw
  databases are excluded. Photos remain an explicit manual ZIP export.
- The app shows when a snapshot was **prepared for Android**, not a claimed
  successful cloud upload. Check Android Settings for backup completion. Android
  controls timing, transport and account; FitNotes has no Google account token
  or Drive API integration.
- To keep another copy now, choose **Export a copy to Drive or a file**, then
  choose Drive in the system picker. This is also useful before major changes.

## Recovery and limits

When Android restores the app's snapshot during supported reinstall/device
setup, FitNotes validates it and opens the existing restore review. Nothing
replaces training until **Replace & restore** is tapped. Cancellation retains
the recovered file for **Review recovered backup**. A pending recovered file
cannot be overwritten by a new automatic backup. After a successful restore it
is removed; enable automatic backup again on a fresh device. Automatic health
sync waits while a recovered snapshot is awaiting review.

The agent serializes the existing versioned JSON backup in one Room transaction,
compresses it and validates the complete candidate before handing the file to
Android. It reads the database without rewriting training. Photo metadata never
enters this JSON. Only `files/system-backup/training.json.gz` is eligible in the
legacy and Android 12+ rules; the agent also enforces consent and encrypted
transport or device transfer. Automatic backup requires Android 9+; older devices
retain manual export. The temporary payload is deleted after backup. Local
consent and status preferences are excluded from cloud/device extraction.

Compressed snapshots are limited to 24 MiB (below Android's 25 MB quota), with the
existing 50 MB expanded JSON limit. Oversized data fails with a manual-export
fallback, without truncating history. Quota failure retains the previous Android
backup. Data restored from newer unsupported formats is rejected, rather than
guessed or partly imported. Restore still requires the same application identity
and compatible signing setup.

Sources: [Android Auto Backup](https://developer.android.com/identity/data/autobackup),
[backup agent](https://developer.android.com/reference/android/app/backup/BackupAgent),
[Android backup testing](https://developer.android.com/identity/data/testingbackup),
[system document picker](https://developer.android.com/training/data-storage/shared/documents-files).
