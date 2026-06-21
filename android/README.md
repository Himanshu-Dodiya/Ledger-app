# Ledger Collector (Android)

A dedicated **SMS collector** for Ledger. It reads bank/UPI transaction SMS, stores them
locally (Room), classifies which are transactional, and syncs them to the backend. The web
app remains the dashboard/reporting UI; this app is purely the ingestion source.

**Phase 1 = local-only proof of concept.** No login/tokens; the backend sync is **mocked**
(`MockSyncApi` returns success). Everything else is real: SMS reading, Room storage,
WorkManager background pipeline, confidence-based filtering, and the debug UI.

## Stack

Kotlin · Jetpack Compose (Material3) · Room (KSP) · WorkManager · ViewModel + StateFlow ·
DataStore · Repository pattern · manual DI (`AppGraph`).

## Build & run

Open `ledger-android/` in **Android Studio** (Ladybug+). It downloads the SDK, the Gradle
wrapper, and dependencies, then **Run ▶** on a device/emulator with SMS.

Pinned tool versions (bump in Android Studio if it suggests newer): AGP 8.7.2,
Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.10.00, Room 2.6.1, compileSdk 35,
minSdk 26.

## How it works

- **SMS discovery is by provider `_id`**, not timestamp: the cursor `lastProcessedSmsId`
  is stored in DataStore and new messages are `content://sms/inbox` rows with
  `_id > lastProcessedSmsId` (ordered by `_id`). Timestamp-only scanning can miss messages.
- **Room is the source of truth.** Raw SMS are stored permanently and never auto-deleted.
  Inserts are idempotent (unique `smsProviderId`), so re-reading can't duplicate.
- **Offline-safe queue.** Unsynced transactional rows stay queued; failures increment an
  attempt counter and retry on the next run. Nothing is lost.
- **Classification only (no parsing).** A 3-stage confidence score (sender + keyword +
  amount) marks `isTransactional`; real field extraction is the backend's job.

## Phase 2 (backend integration) — remaining work

1. Replace `MockSyncApi` with a real client (OkHttp/Ktor) that POSTs to `/api/sms`
   (`{ text, sender, timestamp }`, `Authorization: Bearer <device token>`). The interface
   and call sites don't change.
2. Device linking / auth (enter or scan a per-user ingest token; store securely).
3. Optional: foreground service + notification for very frequent sync; custom-date import
   picker; per-message sync error surfacing in the UI.

## Structure

```
com.ledger.collector
├─ LedgerApp / Permissions / di.AppGraph     app + manual DI + SMS perms
├─ data/local        Room: SmsMessageEntity, SmsMessageDao, LedgerDatabase
├─ data/prefs        SettingsStore (DataStore): interval, importWindow, lastProcessedSmsId…
├─ data/sms          SmsReader (content://sms/inbox by _id)
├─ data/remote       SyncApi + MockSyncApi + SmsSyncPayload/SyncOutcome
├─ data/repository   SmsRepository (read→classify→store), SyncRepository (queue→upload)
├─ domain/filter     SenderMatcher + TransactionClassifier (confidence)
├─ work              SmsSyncWorker, SmsWorkScheduler, SyncInterval, receiver/BootReceiver
├─ receiver          SmsReceiver (live capture)
└─ ui                MainActivity, home/, settings/, theme/, AppViewModelFactory
```
