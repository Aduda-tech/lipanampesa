# Deploy the backend to the cloud (recommended: Render)

Running one cloud backend is what makes this a **SaaS**: shops register themselves at
`/register`, admins work from Nairobi (or anywhere), cashiers work at their shops — the
only setup any phone needs is *open a link and install the app*.

## Why Render and not Vercel/Netlify?

| Platform | Verdict | Why |
|---|---|---|
| **Render** | ✅ Recommended | Runs a persistent Node process, gives you native HTTPS (Daraja callbacks need it — **no ngrok**), and supports a persistent disk for SQLite |
| **Railway / Fly.io** | ✅ Also good | Same model; map `DB_FILE` to their volume mount |
| **Vercel / Netlify** | ❌ Not suitable | Serverless functions only — no long-running Express server, and the SQLite file would be read-only/ephemeral. Would require a rewrite to serverless + managed Postgres |

## One-click-ish deploy on Render (with this repo's `render.yaml`)

1. Push this repo to GitHub.
2. Render dashboard → **New → Blueprint** → select the repo. Render reads `render.yaml`
   and creates the web service (Node 20, auto-generated `JWT_SECRET`, health check on `/health`).
3. Wait ~2 minutes. You get a URL like `https://lipa-na-mpesa-shop.onrender.com`.
   Render exports it as `RENDER_EXTERNAL_URL`, which the app picks up automatically —
   **no config needed**. (Custom domain later? Set `PUBLIC_URL` in the dashboard.)
4. Verify: open `https://<your-url>/health` → `{"ok":true}`.

### "Payment Information Required"? — plan choice matters

The blueprint defaults to **`plan: free`** (testing) — no card at all. If Render asks for
payment details, the blueprint is declaring **paid resources** (`plan: starter` and/or a
persistent `disk`). You have two clean paths:

| | FREE (default) | STARTER (production) |
|---|---|---|
| Card required | ❌ No | ✅ Yes (~$7/mo + disk) |
| Sleeps when idle | Yes (~15 min; first hit after is slow, ~30–60 s) | No |
| Database | **Ephemeral** — SQLite resets on redeploy/restart; shops/catalogs/sales vanish. Fine for demos | **Persistent disk** (`/var/data`) — data survives |
| Use for | Testing, demos to shop owners | Live money, real shops |

To upgrade later: edit `render.yaml`, swap in the commented **PRODUCTION** block, commit
and push — Render will ask for the card *then*, which is expected.

**Manual fallback (no Blueprint):** Dashboard → **New → Web Service** → connect repo →
Runtime: Node · Build: `cd backend && npm ci` · Start: `cd backend && node src/index.js` ·
Instance Type: **Free** → Create. Add env var `NODE_VERSION=20`. Same result, no card.

## Onboard the first shop (2 minutes, no terminal)

1. Open `https://<your-url>/register` → fill shop name + admin username/password →
   **Create my shop**. The page shows the shop's **unique M-Pesa callback URL**
   (e.g. `https://<your-url>/api/payments/callback/a1b2c3…`). Every shop starts in
   **demo mode** — payments are simulated until real keys are entered.
2. On the shop phone (anywhere in the country): open `https://<your-url>/install` →
   **Download app** → install → tap **Open app & connect** (deep link sets the server;
   the cashier never types a URL).
3. Log in as the new admin, add cashiers (**Add user**), stock the catalog manually,
   by barcode, or via **Import CSV/Excel**. Admin can do all of this from Nairobi;
   the cashier just sells.

## Taking a shop live with its own till/paybill

1. The shop completes Daraja **go-live** at https://developer.safaricom.co.ke
   (they get: consumer key, consumer secret, shortcode, passkey).
2. Admin calls the settings endpoint (curl shown in `docs/API.md`) —
   `PUT /api/shops/me` with the keys and `use_mock:false`, `daraja_env:"production"`.
   The endpoint refuses to leave demo mode until all keys are present.
3. Paste the shop's **callback URL** (shown by `GET /api/shops/me` and at registration)
   into the Daraja portal's passkey/callback configuration.
4. Done — that shop's payments now land on **its own M-Pesa line**, while other shops
   can still be in demo or sandbox. Callback URLs are rotatable per shop via
   `POST /api/shops/me/regenerate-key`.

> Platform sandbox keys (optional): set `DARAJA_CONSUMER_KEY` / `DARAJA_CONSUMER_SECRET`
> etc. as Render env vars so **every new shop can immediately do real sandbox tests**
> before bringing its own keys. Without them, new shops stay in simulated demo mode.

## Updating the app on hosted phones

Drop new builds into `backend/public/app.apk` (it's served at `/app.apk` and linked on
`/install`) — or just share the GitHub Actions APK artifact link. Phones on mobile data
download directly; no store account ever needed.

## Security checklist for a SaaS deployment

- [ ] HTTPS only (Render does this; keep `cleartextTrafficPermitted` false in a release build — see `docs/BUILD_APK.md`)
- [ ] Strong `JWT_SECRET` (blueprint auto-generates one)
- [ ] Shops get masked secrets via `GET /api/shops/me`; rotate callback keys if exposed
- [ ] Change the seeded demo-shop passwords or delete the demo shop
- [ ] Optional: restrict `POST /api/shops/register` to an invite code (10/hour/IP limit built in)
- [ ] Backups: snapshot the `/var/data` disk, or `GET /api/transactions/export.csv` regularly
