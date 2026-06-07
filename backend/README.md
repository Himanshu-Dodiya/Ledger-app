# ledger-api — Go backend

Standalone REST API service for the Ledger Android app. Handles JWT-authenticated requests from the app, runs Gmail sync on a schedule, categorises transactions via keyword rules + Gemini LLM, and sends FCM push notifications.

## Stack

Go 1.24 · [chi](https://github.com/go-chi/chi) router · [pgx/v5](https://github.com/jackc/pgx) · Supabase Postgres · Firebase Admin SDK · Gemini 2.5 Flash

## Prerequisites

- Go 1.24+
- A running Supabase project (schema already applied)
- `.env` file — copy `.env.example` and fill in values

## Run locally

```bash
cp .env.example .env
# Fill in .env with your values
go run ./cmd/api
```

Server starts on `PORT` (default 8080). Verify:

```bash
curl http://localhost:8080/v1/health
```

## Other commands

```bash
make build   # compile to bin/api and bin/worker
make test    # go test ./...
make vet     # go vet ./...
make docker  # docker build -t ledger-api .
make health  # curl /v1/health against localhost
```

## Environment variables

Copy `.env.example` → `.env`. Required variables:

| Variable | Description |
|----------|-------------|
| `SUPABASE_URL` | Your Supabase project URL |
| `DATABASE_URL` | Supabase Postgres connection string (pooler recommended) |
| `TOKEN_ENCRYPTION_KEY` | 64-char hex key for encrypting Gmail refresh tokens (`openssl rand -hex 32`) |
| `GOOGLE_CLIENT_ID` | Web OAuth client ID (for Gmail connect + token exchange) |
| `GOOGLE_CLIENT_SECRET` | Web OAuth client secret |

Optional:

| Variable | Description |
|----------|-------------|
| `SUPABASE_JWT_SECRET` | Only needed for HS256 token fallback (modern Supabase uses ES256 via JWKS) |
| `GEMINI_API_KEY` | Enables LLM categorisation; falls back to keywords if blank |
| `FIREBASE_SERVICE_ACCOUNT_PATH` | Path to Firebase service-account JSON for FCM push |
| `GMAIL_SYNC_INTERVAL_MINUTES` | Gmail poll interval (default: 15) |
| `PORT` | HTTP port (default: 8080) |

## API endpoints

All routes under `/v1/` require `Authorization: Bearer <supabase-access-token>` except `/v1/health`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v1/health` | Health check |
| `GET` | `/v1/transactions` | List transactions (filters: `reviewed`, `source`, `category`, `from`, `to`, `q`, `cursor`, `limit`) |
| `PATCH` | `/v1/transactions/{id}` | Update category / reviewed / merchant |
| `POST` | `/v1/transactions` | Create manual transaction |
| `DELETE` | `/v1/transactions/{id}` | Delete transaction |
| `GET` | `/v1/dashboard` | Monthly spend/income totals, by-category, top merchants |
| `GET` | `/v1/categories` | List all categories |
| `POST` | `/v1/ingest/sms` | Ingest raw SMS text → parse → categorise → insert |
| `POST` | `/v1/gmail/connect` | Exchange serverAuthCode for refresh token → store encrypted |
| `POST` | `/v1/gmail/sync` | Trigger immediate Gmail sync for current user |
| `GET` | `/v1/gmail/status` | Gmail connection status + last sync time |
| `DELETE` | `/v1/gmail` | Disconnect Gmail |
| `POST` | `/v1/devices` | Register FCM token |
| `GET` | `/v1/devices` | List registered devices |
| `DELETE` | `/v1/devices/{id}` | Revoke device |

## Database migrations

The main schema lives in the Supabase project (run once, already applied). Additional migrations in `migrations/`:

```bash
# Run in Supabase SQL editor or psql
psql $DATABASE_URL -f migrations/002_devices.sql
```

## Authentication

Tokens are verified via Supabase JWKS (`{SUPABASE_URL}/auth/v1/.well-known/jwks.json`). Modern Supabase projects sign with **ES256** (asymmetric ECC); the middleware handles ES256, RS256, and HS256 automatically. Keys are cached in-memory and refreshed on rotation.

## Deploy

### Fly.io

```bash
fly auth login
fly apps create ledger-api
fly secrets set SUPABASE_URL=... DATABASE_URL=... TOKEN_ENCRYPTION_KEY=... \
  GOOGLE_CLIENT_ID=... GOOGLE_CLIENT_SECRET=...
fly deploy
```

`fly.toml` is pre-configured for `bom` (Mumbai) region, 256 MB RAM, auto-stop/start.

### Railway

`railway.toml` is included. Connect the repo in Railway dashboard, add environment variables, and deploy.

## Project structure

```
cmd/
  api/        HTTP server entrypoint
  worker/     Background Gmail scheduler (can also run in-process)
internal/
  auth/       Supabase JWT middleware (ES256/RS256/HS256 + JWKS)
  categorize/ Transaction categorisation (keyword rules + merchant normalisation)
  config/     Env config + validation
  db/         pgx pool + query helpers
  devices/    FCM token registration
  gmail/      OAuth token exchange, Gmail REST API, sync loop, AES-256-GCM encryption
  httpx/      JSON helpers, error envelope, request logging, CORS
  ingest/     SMS / email ingest handlers
  llm/        Gemini 2.5 Flash structured categorisation
  model/      Shared domain types
  notify/     Firebase Cloud Messaging sender
  parser/     SMS/email parser (amount, merchant, date, ref, payment method)
  transactions/ Transaction service and HTTP handlers
migrations/   Additive SQL migration files
```
