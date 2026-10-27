# FitLogs privacy

FitLogs stores workout history, measurements, imported activity records and
progress photos on your Android device. You can use the workout logger without
a FitLogs account or a developer-operated server.

- **Google dashboard (optional):** when you connect Google, FitLogs copies your
  completed training records into a spreadsheet and folder in the Google
  account you choose. Other FitLogs users do not get access. FitLogs requests
  access only to files it creates or that you explicitly open with it. Notes and
  body measurements are excluded unless you opt in. Photos, birth dates, body
  profiles, raw source payloads and heart-rate samples are excluded.
- **Credentials:** Google handles account selection and consent. FitLogs does
  not ask for your Google password. Access tokens are used for Google API
  requests and are excluded from training exports and backups. The developer
  does not receive your Google token or spreadsheet contents.
- **Disconnect and deletion:** disconnecting stops future Google sync. Existing
  copies remain in your Google Drive until you delete them there. You can also
  revoke FitLogs access in your Google account settings. Deleting a local record
  is reflected in a later successful dashboard sync; separately saved copies
  are under your control.
- **Health Connect:** Android permissions control access to source activity and
  health records. Imported records remain local unless you choose an export or
  optional dashboard copy. Raw heart-rate samples are not dashboard content.
- **Exports and backups:** exports go to the destination you choose. Photo
  inclusion and Android system backup are optional. A Google dashboard is not
  a complete backup of your local database or photos.
- **Network requests:** update checks and the program catalog contact public
  GitHub resources. Google requests go directly to Google. FitLogs has no
  advertising or sale of training data.

Google's own [privacy policy](https://policies.google.com/privacy) applies to
files stored in Google Drive and Sheets. Google API data is used only for the
dashboard feature, consistently with the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including its Limited Use requirements.

For questions or problems, open an issue in the
[FitLogs repository](https://github.com/fit-logs/fitlogs/issues). Avoid posting
private workout exports, photos, credentials or health records in public issues.
