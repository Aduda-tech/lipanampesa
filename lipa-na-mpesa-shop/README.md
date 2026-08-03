# 🟢 Lipa na M-Pesa Shop Pay — multi-shop SaaS

An Android app + cloud backend that lets **any shop** prompt customers to pay by M-Pesa (STK Push) via Safaricom's Daraja API. Cashiers pick items (dropdown/barcode), enter the customer's phone, tap **PROMPT** — the customer confirms with their M-Pesa PIN — and the sale, including the **M-Pesa confirmation number**, lands in the shop's own transactions database.

**v2.0 is a true SaaS:** one hosted backend serves **many independent shops**. Each shop has its own catalog, staff, transactions and **its own Daraja keys (money lands on its own till/paybill)**. Deploy once on Render, then every onboarding is self-serve: owner registers at `/register`, cashiers install the app from `/install` and tap a deep link that configures everything for them. The admin can be in Nairobi; the cashier can be in Lodwar.

```
┌─────────────┐      /api/payments/prompt      ┌──────────────┐     STK Push      ┌─────────────┐
│ Android app │ ─────────────────────────────▶ │   Backend    │ ────────────────▶ │   Safaricom │
│  ( Kotlin ) │ ◀──── status polling ──────── │ Node+SQLite  │   Daraja API      │   (Daraja)  │
└─────────────┘                                 └──────┬───────┘                    └──────┬──────┘
       ▲                                               │            callback (receipt)      │
       │            customer taps PIN on phone ◀────────┴────────── M-Pesa prompt ◀─────────┘
       └────────  DB row: SUCCESS + mpesa_receipt, recorded for future reference
```

## ✨ Features

**v2.1.0 highlights**

- **🧾 One-form Daraja setup** — a shop admin enters plain-English till details (**business type, till/paybill number, store number, operator name, account ref**) + their portal keys once in the app (Admin → *M-Pesa / Daraja settings*). The server **auto-derives everything else**: STK transaction type (`CustomerPayBillOnline` vs `CustomerBuyGoodsOnline`), PartyB (incl. **store number** for HQ/store till networks), password generation and the callback URL to paste. Validated before a shop can leave demo mode; secrets are masked & never clobbered — see `docs/DARAJA_FOR_SHOPS.md`

**v2.0.0 highlights**

- **🏪 Multi-tenant SaaS** — `shops` table isolates every tenant: own catalog, users, transactions, price history. Cross-shop access is impossible (404, tested)
- **💳 Per-shop Daraja keys** — each shop charges to its own till or paybill; demo(mock) ⇄ sandbox ⇄ production is a **per-shop toggle** via `PUT /api/shops/me`. New shops start in demo mode and go live when they paste their keys
- **🔗 Unique callback URL per shop** (`/api/payments/callback/<shopKey>`, rotatable) — Safaricom confirmations always route to the right tenant
- **☁️ Cloud-first deploy** — `render.yaml` blueprint (persistent disk, health check, auto `PUBLIC_URL`); native HTTPS means **Daraja callbacks work with zero ngrok**
- **📝 Self-serve onboarding** — `/register` page creates a shop + admin in seconds; login returns the shop name (shown in the app's title bar)
- **🪄 Zero-typing cashier setup** — `/install` page now has an **"Open app & connect" deep link** (`mpesashop://connect?url=…`) that installs the server URL into the app automatically. QR & manual remain as fallbacks

**v1.2.0 highlights**

- **📡 Zero-setup networking** — the app **auto-detects the backend server on the shop Wi-Fi/hotspot by itself** (mDNS/DNS-SD broadcast `_mpesa-shop._tcp.local`). No IP typing. Fallbacks: **setup QR scan** (server prints the QR to its console) or manual URL
- **🚫 No bundles on shop phones** — the whole app↔server link runs on local Wi-Fi/hotspot (free). Only the *server machine* needs any internet for Daraja. Customers' phones never need bundles (STK Push is a SIM/GSM message). Full guide: **`docs/ZERO_DATA_SETUP.md`**
- **📦 LAN app distribution** — server hosts the APK at `/app.apk` plus a friendly **`/install` onboarding page** (download + QR + demo logins), so cashiers onboard straight from the shop Wi-Fi — no Play Store, no Google account, no data

**v1.1.0 highlights**

- **🛒 Multi-item cart checkout** — add several lines (dropdown or barcode), one STK Push bills the total in a single customer prompt; tap a cart line to remove it; scanned products drop straight into the cart
- **🧾 Itemised, sharable receipts** — after payment the cashier can share a formatted receipt (lines, tax, total, **M-Pesa confirmation number**) to the customer via WhatsApp/SMS
- **💰 Per-item tax rates** — set e.g. `0.16` (16% VAT) per item or via import; tax is calculated at checkout, stored per transaction, and included in summaries & exports
- **🔐 Hardened API** — security headers (helmet), login/prompt rate limiting, DB-level guard so one M-Pesa receipt can never be recorded twice
- **Extra polish** — cashier "today" chip, pull-to-refresh transaction history with tap-for-details, offline catalog cache, admin "add user" screen, date-range filters on the API

**Core**

- **STK Push checkout** — item dropdown auto-fills price; quantity × price = total; one tap prompts the customer's phone
- **Automatic completion** — Daraja callback marks the transaction `SUCCESS` and stores the **`mpesa_receipt` (confirmation number)** in SQLite
- **Roles by username** — `admin` (manage catalog/prices/tax/users/exports) and `cashier` (sell)
- **Catalog your way** — manual entry, **camera barcode scanning**, or **CSV/Excel bulk import** (barcode match updates prices — no duplicates)
- **Price management** — admin edits with a full `price_history` audit trail
- **M-Pesa green UI** — English ⇄ Kiswahili switch, display-currency switcher (KES default + USD/EUR/GBP/TZS/UGX/RWF)
- **Transaction database** — searchable history, daily summary, CSV export for the books
- **Demo mode** (`DARAJA_MOCK=true`, the default): the full flow works **without any Safaricom account** — simulated STK pushes auto-complete in ~12 s with a fake receipt
- **APK builds itself** — the GitHub Action uploads an `app-debug.apk` artifact on every run, and attaching a tag (`git tag v1.1.0 && git push --tags`) creates a **GitHub Release with the APK attached**

## 📁 Repo layout

```
lipa-na-mpesa-shop/
├── backend/               Node.js + Express + SQLite API
│   ├── src/
│   │   ├── index.js       server bootstrap & route wiring
│   │   ├── config.js      env-driven config (sandbox ⇄ production)
│   │   ├── daraja.js      Daraja client (OAuth, STK Push, STK Query, callback parser)
│   │   ├── db.js          SQLite schema + sample catalog + default users
│   │   ├── middleware.js  JWT auth + admin guard
│   │   └── routes/        auth / items / payments / transactions
│   ├── .env.example       copy to .env — all settings explained inline
│   └── Dockerfile
├── android-app/           Kotlin Android app (Retrofit, ZXing scanner)
│   └── app/src/main/      Login, Cashier, Admin, Transactions screens
├── docs/
│   ├── SANDBOX_VS_PRODUCTION.md   the one-flag switch + go-live checklist
│   ├── BUILD_APK.md               3 ways to get the APK (incl. GitHub Actions)
│   ├── USER_GUIDE.md              staff handbook (EN)
│   └── API.md                     endpoint reference + curl examples
├── samples/items-import-template.csv
├── .github/workflows/     android-apk.yml (builds the APK) + backend-ci.yml
└── docker-compose.yml
```

## ☁️ Deploy to the cloud (recommended) — then it's all self-serve

Push to GitHub → Render dashboard → **New → Blueprint** → pick the repo (`render.yaml` included) → done in ~2 min at `https://<your-app>.onrender.com`. Then:

1. Shop owner opens `https://<your-app>/register` → creates shop + admin (starts in **demo mode**).
2. Cashier phones open `https://<your-app>/install` → **Download app** → tap **"Open app & connect"** → log in. That's the entire phone setup — works from anywhere, on Safaricom data or shop Wi-Fi.
3. When a shop is ready for real money, admin pastes its Daraja keys (`PUT /api/shops/me`) and the shop's callback URL into the Daraja portal.

Full guide incl. why Render (native HTTPS + persistent disk) and why **not Vercel** (serverless, no SQLite persistence): **`docs/DEPLOY_CLOUD.md`**.

## 🚀 Quickstart locally (5 minutes, demo mode)

**Prerequisites:** Node.js 18+.

```bash
cd backend
cp .env.example .env      # ships with DARAJA_MOCK=true — nothing else needed
npm ci
npm start                 # → http://localhost:3000
```

Then build & run the Android app (see `docs/BUILD_APK.md`), and either register your own shop at `http://localhost:3000/register` or use the seeded **demo shop** accounts:

| Username | Password | Role | Shop |
|---|---|---|---|
| `admin` | `admin123` | admin | Demo Shop |
| `cashier` | `cashier123` | cashier | Demo Shop |

Pick *Bluetooth Earbuds*, quantity 1, phone `0712345678`, tap **PROMPT PAYMENT** and watch it
turn green with a mock receipt ~12 s later. Now flip to real M-Pesa when ready — see below.

> On a real phone, set the **Backend server URL** on the login screen to your PC's LAN IP,
> e.g. `http://192.168.1.50:3000/` (emulator uses the pre-filled `http://10.0.2.2:3000/`).

## 🔄 Sandbox ⇄ Production (one flag)

Everything is driven by `backend/.env`:

| You want | Set |
|---|---|
| Demo (no Safaricom account) | `DARAJA_MOCK=true` |
| Real **sandbox** tests | `DARAJA_MOCK=false`, `DARAJA_ENV=sandbox` + keys from https://developer.safaricom.co.ke (test shortcode `174379` already in `.env.example`) |
| **Live** money | `DARAJA_MOCK=false`, `DARAJA_ENV=production` + production app keys, shortcode & passkey |

Full URLs, ngrok callback setup and the go-live checklist: **`docs/SANDBOX_VS_PRODUCTION.md`**.

## ⬆️ Push to GitHub (then get the APK artifact)

```bash
git init
git add .
git commit -m "Lipa na M-Pesa Shop Pay"
git branch -M main
git remote add origin https://github.com/<you>/lipa-na-mpesa-shop.git
git push -u origin main
```

GitHub → **Actions** → “Android APK” → **Artifacts** → `app-debug-apk`. Install on any Android 8+ phone.

## 🔐 Security notes

- M-Pesa secrets live **only** in `backend/.env` on the server — never in the APK.
- Passwords are bcrypt-hashed; API is JWT-protected; admin endpoints are role-guarded.
- `backend/.env` and the SQLite DB are git-ignored. Change `JWT_SECRET` and default passwords before production.
- The APK allows cleartext HTTP for LAN development — switch it off and serve HTTPS for production (checklist in docs).

## 🧰 Stack

Backend: Node.js 20, Express, better-sqlite3, JWT, bcrypt, multer + SheetJS (CSV/XLSX import).  
Android: Kotlin, Retrofit/OkHttp, Material 3, ZXing barcode scanner, per-app locales (EN/SW).  
Payments: Safaricom **Daraja** Lipa Na M-Pesa Online (STK Push) — sandbox ⇄ production via env flag.

## License

MIT — see `LICENSE`. Adapt it to any shop, any industry.
