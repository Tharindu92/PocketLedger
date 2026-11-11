# Play Console answers: App content

## Data safety

Pocket Ledger's own data never leaves the phone. The answers below cover what Google ML Kit /
Play services (text recognition, document scanner) send. Source:
https://developers.google.com/ml-kit/android-data-disclosure

**Does your app collect or share any of the required user data types?** Yes

**Is all of the user data collected by your app encrypted in transit?** Yes

**Do you provide a way for users to request that their data is deleted?** No
(Nothing is held by us; on-phone data is deleted by uninstalling or in the app.)

Data types to tick:

| Category | Type | Collected | Shared | Processed ephemerally | Required / optional | Purpose |
|---|---|---|---|---|---|---|
| App info and performance | Diagnostics | Yes | No | No | Required | Analytics |
| Device or other IDs | Device or other IDs | Yes | No | No | Required | Analytics |

Not collected (stays on the phone, so it doesn't count as "collected"): financial info, photos,
messages/notifications, app activity.

**Exchange rates:** requests contain only a currency code and a date, which is not a user data
type.

## Other App content declarations

| Section | Answer |
|---|---|
| Privacy policy | https://tharindu92.github.io/PocketLedger/privacy |
| Ads | No, the app contains no ads |
| App access | All functionality is available without special access (no login) |
| Content rating | Questionnaire category "Utility, productivity, communication or other"; answer No to everything → rated Everyone / PEGI 3 |
| Target audience | 18 and over (also fine: 16–17, 18+; don't pick under 13) |
| News app | No |
| Government app | No |
| Financial features | Pick "Personal finance: budgeting / expense tracking" (or the closest "doesn't provide financial services" option; the app moves no money) |
| Health | No |
| Data safety | See above |
| Advertising ID | No (the app doesn't use it) |

## Notification access

There is no separate Play declaration form for NotificationListenerService. The app shows a
prominent disclosure (welcome screen and bank screen) before opening Android's notification
access settings, which is what the User Data policy requires.
