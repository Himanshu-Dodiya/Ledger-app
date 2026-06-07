# Ledger

An Android-first personal finance tracker that automatically captures transactions from bank SMS and Gmail, categorises them using keyword rules and an LLM fallback, and lets you review and manage your spending — all from your phone.

## Repository structure

```
Ledger-app/
├── backend/    Go API service (ledger-api)
└── android/    Android app (Kotlin / Jetpack Compose)
```

## Architecture

```
Android App (Kotlin/Compose)          Go Service (ledger-api)            Supabase
────────────────────────────          ──────────────────────             ─────────
supabase-kt  ── auth only ──────────────────────────────────────────▶   Auth (JWT)
LedgerApiClient (OkHttp)  ─Bearer──▶  chi router + JWT middleware        Postgres
  transactions / dashboard              ├─ transactions                   (RLS off;
  ingest / gmail / devices              ├─ ingest (SMS / email)            scoped by
Room  ◀──── offline cache              ├─ parser + categorise + LLM        user_id)
WorkManager (SMS background)           ├─ gmail (OAuth, sync)
FCM  ◀────────── push ─────────────── └─ devices + notify
```

- **Identity**: Supabase Auth — email/password, Google Sign-In, password reset via deep link.
- **Data store**: Supabase Postgres — same schema used by both the Go service and (optionally) a Next.js dashboard.
- **SMS capture**: Android reads `content://sms/inbox`, classifies, queues offline, posts to `/v1/ingest/sms`.
- **Gmail sync**: user connects Gmail once from Settings; the Go service syncs on a schedule even when the app is closed.
- **Categorisation**: keyword rules → LLM fallback (Gemini 2.5 Flash) → "Uncategorized".
- **Push notifications**: FCM notifies on new uncategorised transactions.

## Quick start

See detailed instructions in each sub-project:

- [`backend/README.md`](backend/README.md) — run/deploy the Go service
- [`android/README.md`](android/README.md) — build and run the app

## Required external services

| Service | Purpose |
|---------|---------|
| [Supabase](https://supabase.com) | Auth + Postgres database |
| [Google Cloud](https://console.cloud.google.com) | OAuth (Gmail, Google Sign-In) |
| [Firebase](https://console.firebase.google.com) | FCM push notifications |
| [Gemini API](https://aistudio.google.com) | LLM categorisation (optional) |
| Fly.io / Railway | Go service hosting |
