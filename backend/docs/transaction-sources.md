# Transaction Capture Sources — Architecture & Findings

Status: **design / proposal**. This document is the deliverable for Issue 3 ("investigate all
realistic and maintainable transaction sources beyond SMS") and Issue 4 ("UPI notes"). It records
what works today, what doesn't, and the recommended path forward. No new ingest service is shipped
in this round; the `note` field plumbing is the only code landed so far.

---

## 1. The problem

SMS alone cannot give complete transaction coverage. In practice on Indian UPI:

- **GPay / PhonePe / Paytm / BHIM** P2P and merchant-QR payments often produce **no bank SMS** at
  all (especially small amounts, or when the bank suppresses per-txn SMS).
- The **same payment** may arrive as: a bank SMS, **or** only a Gmail receipt, **or** only an
  in-app push notification from the UPI app — sometimes more than one, sometimes exactly one.
- The user-entered **note/remark** ("Pizza", "Rent", "Fuel") — frequently the best categorization
  signal — is almost never in the bank SMS, sometimes in the Gmail receipt, and reliably present
  **only in the UPI app's own notification**.

So we need multiple capture channels feeding one deduplicated pipeline.

---

## 2. Sources evaluated

| Source | Coverage | Note/remark? | Stability | Permission cost | Verdict |
|---|---|---|---|---|---|
| **Bank SMS** (shipped) | Bank-routed txns only | Rare | High | `READ_SMS` (sensitive) | Keep — baseline |
| **Gmail receipts** (shipped) | Merchant/email receipts | Sometimes | High | OAuth `gmail.readonly` | Keep — server-side sync |
| **NotificationListenerService** | **GPay/PhonePe/Paytm/BHIM, incl. note** | **Yes** | Medium (per-app templates) | `BIND_NOTIFICATION_LISTENER_SERVICE` | **Recommended next** |
| Account Aggregator (Setu/Finvu) APIs | Bank-grade, all accounts | No note | High | Regulated consent flow | Future — best long-term accuracy |
| UPI app official APIs | — | — | — | None public for read | Not available |
| Accessibility screen-scraping | Broad | Yes | Very low | `BIND_ACCESSIBILITY_SERVICE` | **Rejected** (see §6) |

---

## 3. Recommended architecture: NotificationListenerService

A foreground-independent `NotificationListenerService` reads posted notifications from a
**whitelist** of UPI/bank package names, parses them **on-device**, and posts the result to the
backend. It runs even when the Ledger app is closed.

```
UPI app posts notification
        │  (StatusBarNotification: title, text, bigText, package, postTime)
        ▼
LedgerNotificationListener (whitelist filter by package)
        │  on-device parse → {amount, direction, merchant, note, account?, ref?}
        ▼
Room queue (reuse SmsMessageEntity-style offline queue)  ← idempotent, retryable
        │  WorkManager drain
        ▼
POST /v1/ingest/notification  {source:"notification", app, title, text, note, postedAt}
        │  backend parser.ParseText(...) + note → categorize → dedupe_hash → insert
        ▼
transactions (source = 'notification', reviewed = false)
```

### Why this shape
- **Reuses the existing pipeline**: the same `parser.ParseText`, `categorize.ResolveCategory`, and
  `dedupe_hash` (ref/amount/merchant basis) the SMS path already uses — so a UPI txn captured via
  notification **and** a duplicate bank SMS collapse to one row automatically.
- **Offline-safe**: mirrors the proven SMS queue (`SmsMessageEntity` + `SyncRepository`) — nothing
  is lost if the network is down.
- **On-device parse first** so we never upload raw notification content we can't use; the backend
  re-parses authoritatively (single source of truth stays the Go parser).

---

## 4. Implementation checklist (for the build round)

**Android**
1. `AndroidManifest.xml`: declare the service with
   `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and intent-filter
   `android.service.notification.NotificationListenerService`.
2. `services/LedgerNotificationListener.kt`: `onNotificationPosted` → filter by package whitelist:
   `com.google.android.apps.nbu.paisa.user` (GPay), `com.phonepe.app`, `net.one97.paytm`,
   `in.org.npci.upiapp` (BHIM), plus bank apps as discovered. Extract `EXTRA_TITLE`,
   `EXTRA_TEXT`, `EXTRA_BIG_TEXT`.
3. `domain/filter/NotificationParser.kt`: per-app templates → amount, direction, counterparty,
   **note**. Keep templates small and table-driven; treat unknown layouts as "queue raw, let
   backend parse".
4. Queue into Room (new `NotificationEventEntity` or reuse the SMS queue with a `source` column) +
   WorkManager drain to the new endpoint.
5. Settings: a "Capture from UPI apps" toggle that deep-links to the system
   **Notification access** settings screen (`ACTION_NOTIFICATION_LISTENER_SETTINGS`) with a clear
   explanation of what's read and why.

**Backend**
6. `internal/ingest/handler.go`: add `POST /v1/ingest/notification` (reserved in the plan) — same
   body shape as SMS plus `app`, `note`; parse → categorize → dedupe → insert with
   `source = 'notification'`. Register in `cmd/api/main.go`.
7. `parser.ParseText` already returns `Note`; ensure notification text flows through the note +
   merchant extraction added in Issue 1.

---

## 5. UPI notes — findings (Issue 4)

- **SMS**: notes are rarely present. When a remark survives it's usually packed into the reference
  tail (e.g. RBL salary credit `...409879005533/Salary for May26`). Issue 1's parser extracts that
  trailing `/<text>` and `for <text>` into the new `note` field.
- **Gmail receipts**: notes appear inconsistently depending on the merchant/PSP template.
- **UPI app notifications**: the note is **reliably** present (it's literally what the user typed:
  "Pizza party", "Rent"). This is the strongest reason to ship the NotificationListener.
- **Storage + use**: `note` is now a first-class column (`transactions.note`) plumbed through
  Room/DTO/backend. Categorization should consult the note **before** merchant keywords — a note of
  "Pizza" should win over an opaque VPA. (Wired in Issue 4's `categorize` change.)

---

## 6. Why not accessibility scraping

`BIND_ACCESSIBILITY_SERVICE` could read on-screen payment text, but it is rejected because:
- **Play policy**: accessibility APIs may only be used to assist users with disabilities; financial
  scraping is a flagged violation and risks app removal.
- **Fragility**: breaks on every UPI-app UI change; high maintenance.
- **Trust/security**: an accessibility service can read *everything* on screen — a large,
  unjustified privacy surface for a finance app.

The NotificationListener delivers the same coverage (UPI apps already surface the txn in a
notification) at a far lower policy, privacy, and maintenance cost.

---

## 7. Recommended roadmap

1. **Now (this round)**: `note` field end-to-end + improved SMS/Gmail extraction (Issues 1 & 4).
2. **Next**: ship the NotificationListener per §4 — biggest coverage gain for UPI.
3. **Later**: Account-Aggregator integration (Setu/Finvu) for authoritative, bank-grade coverage of
   all accounts via regulated consent — the long-term accuracy ceiling.