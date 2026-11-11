---
title: Pocket Ledger – Privacy policy
---

# Pocket Ledger – Privacy policy

*Effective 11 November 2026*

Pocket Ledger is an Android app for recording bills, expenses and income. It is developed by
Tharindu92, the developer named on the app's Google Play page ("we").

**In short:** Pocket Ledger has no account and no server. Your entries, bill photos and settings
are stored only on your phone. We never receive them.

## What the app stores, and where

Everything you enter or the app creates is kept in the app's private storage on your phone, which
other apps cannot read:

- entries (amount, date, payee, category, notes, bill number, tax, payment method)
- bill photos you take or pick, and the text read from them
- profiles, categories, category rules and settings
- bank bookings detected from notifications (see below)
- exchange rates you downloaded or typed in

Uninstalling the app, or clearing its storage in Android settings, deletes all of it. You can also
delete single entries in the app at any time.

## Camera and photos

Bill photos are taken with the camera app or Google's document scanner, or picked with the
Android photo picker. The app sees only the photos you choose. Text on the bill is read **on the
phone** with Google ML Kit; photos are not uploaded.

## Bank notifications (optional)

If you turn this on and grant Android's "notification access", Pocket Ledger reads the
notifications of the banking or payment apps **you select**, to suggest them as entries. From those
notifications it keeps only the amount, currency, payee and time. Notifications of other apps, and
notifications from your banking apps that are not payments (for example security codes), are ignored and
not stored. Nothing from your notifications leaves the phone. You can turn this off at any time
in Android's notification access settings.

## Internet use

- **Exchange rates (optional).** If you allow it, the app downloads daily exchange rates from the
  free currency-api project via `cdn.jsdelivr.net` or `currency-api.pages.dev`. The request contains
  only a currency code and a date. As with any web request, these services see your IP address.
- **Google ML Kit / Google Play services.** The on-device text recognition and document scanner
  send Google diagnostic and usage data: device model and Android version, performance
  (for example processing time), API configuration, error codes, and a per-installation identifier
  that is not intended to identify you. They do not send your photos or the text on them. This is
  described in Google's
  [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) and handled
  under [Google's Privacy Policy](https://policies.google.com/privacy).

The app contains no advertising, no analytics of its own and no tracking.

## Sharing

We do not sell or share your data. Data leaves the app only when **you** choose to:

- exporting or sharing a statement (PDF or CSV) to another app or person
- saving a backup file to a place you pick, such as Google Drive; you can protect it with a
  password (AES-256 encryption)
- Android's own backup to your Google account, if turned on in your phone's settings. Pocket
  Ledger allows it only when the backup is end-to-end encrypted with your screen lock.

## Security

Data is kept in the app's private storage. An optional app lock asks for your fingerprint, face or
screen lock, hides the app in the recent-apps view and keeps amounts off the lock screen.
Exchange-rate downloads use HTTPS.

## Children

Pocket Ledger is not directed at children under 13.

## Changes

If this policy changes, the new version is published here with a new effective date.

## Contact

Questions or requests: open an issue at
[github.com/Tharindu92/PocketLedger/issues](https://github.com/Tharindu92/PocketLedger/issues).
