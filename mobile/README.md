# Polite Scraper (Android / Flutter)

The on-device version of the scraper. It runs entirely on the phone, with no
server. The same rules apply: it reads robots.txt, waits between requests,
stays on public addresses, and never logs in or bypasses blocks.

## Get the APK

1. Push to `arena/bed5214f-coo` (or run the workflow manually).
2. Open the **Actions** tab, choose **Build Android APK**, and download the
   `polite-scraper-apk` artifact.
3. Copy `app-release.apk` to your phone and install it. You may need to allow
   installs from unknown sources.

The release APK is signed with the debug key, so it installs directly but
cannot be uploaded to the Play Store as-is. Add a signing key before that.

## Build locally (optional)

```bash
cd mobile
flutter create --platforms=android --project-name polite_scraper --org com.louaysadok .
flutter pub get
flutter test
flutter build apk --release
```

## Notes

- Android can pause background work when the app is minimised. Keep the app
  open while a scrape runs, or stop and resume it later. Resume re-crawls
  from the start URL and skips pages already stored.
- Backups: Settings → Create backup shares a zip (DB + JSON) to any app or
  cloud. Restore from a backup in the same screen. Stop running scrapes first.
- Limits match the web version: 5,000 pages max, depth 10, at least 0.25 s between requests.
