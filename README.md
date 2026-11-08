# Pocket Ledger — bills & expenses tracker (Android, Kotlin)

Record income and expenses, capture bills by photo, and export a statement.

## Features

1. **Scan a bill** (camera or gallery). On-device OCR (Google ML Kit, works offline) reads
   the bill and pre-fills:
   - total amount (prefers *Grand Total / Amount Due / Net Total / Total*, ignores Sub-total, cash tendered and change)
   - tax (VAT / GST / SSCL)
   - bill / invoice / receipt number
   - business (shop) name
   - bill date (dd/mm/yyyy, yyyy-mm-dd, 12 Mar 2026, Mar 12, 2026, …)
   - payment method (cash, card/Visa/Mastercard, bank transfer, QR)
   - a suggested category

   Every field stays editable. The photo is stored with the entry and can be opened full-screen
   (pinch to zoom); "Re-scan" runs OCR again, and "Show scanned text" shows the raw text.
2. **Manual entry** of expenses or income: amount, date, business/payer, bill no., category,
   tax, payment method and notes.
3. **Statements**: choose this month / last month / this year / last year or a custom range, then
   - **Download PDF**: an A4 statement with summary (income, expenses, net, tax paid),
     breakdowns by category, and a dated transaction ledger with running balance
   - **Download CSV**: opens in Excel or Google Sheets
   - or **Share** either one (email, WhatsApp, Drive, …)

The home screen shows a month at a time with income, expense and net totals, and can filter to
income or expenses only. Settings (gear icon) set the currency code and the business name printed
on statements.

## Tech

Kotlin, Jetpack Compose (Material 3), Room (SQLite), ML Kit Text Recognition, Navigation Compose,
Android `PdfDocument`. minSdk 26, targetSdk 34. All data stays on the device, and the app needs no
runtime permissions: the camera opens through the system camera app and photos are chosen with the
system photo picker.

## Build & run

Open the folder in Android Studio and press Run, or:

```bash
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # bill parser unit tests
```

## Code map

| Path | What |
|---|---|
| `data/` | Room entity `Transaction`, DAO, database, settings, default categories |
| `ocr/ReceiptScanner.kt` | Runs ML Kit OCR on a photo |
| `ocr/ReceiptParser.kt` | Pure-Kotlin heuristics that pull accounting fields from OCR text (unit-tested) |
| `export/StatementExporter.kt` | PDF and CSV statement generation |
| `ui/home`, `ui/edit`, `ui/statement` | Screens + ViewModels |
