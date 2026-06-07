# Ledger Android

The complete Ledger product on Android. Captures bank/UPI transactions from SMS and Gmail, categorises them automatically, and lets you review, manage, and analyse your spending — all from your phone.

## Features

- **SMS capture** — reads bank/UPI SMS in the background, parses transactions, queues them offline, and syncs to the Go backend
- **Gmail sync** — connect Gmail once from Settings; the backend pulls transaction emails on a schedule even when the app is closed
- **Review queue** — uncategorised transactions land in a swipeable inbox; categorise or skip with one tap
- **Transaction history** — full searchable list with source badges (SMS / Gmail / manual), category filters, and date range
- **Dashboard** — monthly spend/income totals, by-category breakdown, top merchants, and to-review count
- **Google Sign-In** — one-tap login alongside email/password; full password-reset flow via deep link
- **Push notifications** — FCM alerts when new uncategorised transactions arrive
- **Device management** — view and revoke registered devices from Settings

## Stack

Kotlin · Jetpack Compose (Material 3) · supabase-kt · Room (KSP) · WorkManager · ViewModel + StateFlow · DataStore · OkHttp · manual DI (AppGraph) · Firebase Messaging

Tool versions: AGP 8.7.2, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.10.00, compileSdk 35, minSdk 26

## Prerequisites

- Android Studio Ladybug (2024.2) or newer
- Android device or emulator with **minSdk 26** (Android 8.0)
- A running [ledger-api](../backend/) instance (local or deployed)
- Supabase project credentials
- `google-services.json` from Firebase Console (FCM)

## Configuration

### 1. `local.properties`

Copy `local.properties.example` → `local.properties` (gitignored):

```properties
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=eyJ...
API_BASE_URL=https://your-api.fly.dev
GOOGLE_WEB_CLIENT_ID=123456789-abc.apps.googleusercontent.com
```

For local device testing set `API_BASE_URL=http://192.168.x.x:8080` (your machine's LAN IP). The app allows cleartext HTTP for development.

### 2. `app/google-services.json`

Download from [Firebase Console](https://console.firebase.google.com) → Project Settings → Your apps → Android app → Download `google-services.json`. Place it at `app/google-services.json`.

### 3. Supabase redirect URL

In Supabase Dashboard → Auth → URL Configuration → Redirect URLs, add:

```
io.ledger.collector://reset-callback
```

This enables the password-reset deep link to return to the app.

## Build & run

Open `android/` in Android Studio. It downloads the SDK and dependencies automatically. Hit **Run ▶** on a device or emulator.

The app requests `READ_SMS` and `RECEIVE_SMS` permissions on first launch — grant them for SMS capture to work.

## How SMS sync works

- **Discovery by provider `_id`**: the cursor `lastProcessedSmsId` is stored in DataStore; new messages are rows from `content://sms/inbox` with `_id > lastProcessedSmsId`. Timestamp-only scanning can miss messages.
- **Room is the source of truth**: raw SMS are stored permanently and never auto-deleted. Inserts are idempotent on `smsProviderId`.
- **Offline-safe queue**: unsynced transactional rows stay queued; failures retry on the next WorkManager run.
- **Sync result visibility**: after each "Sync now" tap, the home screen shows a breakdown: e.g. "5 uploaded, 3 new, 2 duplicate".

## Project structure

```
com.ledger.collector
├── LedgerApp / di.AppGraph          app entry + manual DI
├── data/
│   ├── auth/     AuthRepository (supabase-kt, token refresh)
│   ├── local/    Room: SmsMessageEntity, LedgerDatabase
│   ├── prefs/    SettingsStore (DataStore)
│   ├── remote/   BackendClient (OkHttp), SyncApi, HttpSyncApi
│   ├── repository/  SmsRepository, SyncRepository, TransactionRepository,
│   │                GmailRepository, DeviceRepository
│   └── sms/      SmsReader (content://sms/inbox)
├── domain/filter/   SenderMatcher, TransactionClassifier
├── work/            SmsSyncWorker, SmsWorkScheduler, BootReceiver
├── receiver/        SmsReceiver (live capture)
└── ui/
    ├── auth/        LoginScreen, LoginViewModel
    ├── home/        HomeScreen (dashboard), HomeViewModel
    ├── inbox/       UncategorisedScreen + ViewModel
    ├── history/     HistoryScreen + ViewModel
    ├── settings/    SettingsScreen + ViewModel
    └── MainActivity (nav host, deep link handler, reset password screen)
```
