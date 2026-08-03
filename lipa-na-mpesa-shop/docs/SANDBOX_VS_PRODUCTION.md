# Sandbox ↔ Production — the exact switch

The backend talks to Safaricom's **Daraja API**. There are two environments:

| | Sandbox (testing) | Production (real money) |
|---|---|---|
| Base URL | `https://sandbox.safaricom.co.ke` | `https://api.safaricom.co.ke` |
| OAuth token | `https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials` | `https://api.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials` |
| STK Push | `https://sandbox.safaricom.co.ke/mpesa/stkpush/v1/processrequest` | `https://api.safaricom.co.ke/mpesa/stkpush/v1/processrequest` |
| STK Query | `https://sandbox.safaricom.co.ke/mpesa/stkpushquery/v1/query` | `https://api.safaricom.co.ke/mpesa/stkpushquery/v1/query` |
| Money moved? | ❌ Simulated | ✅ Real M-Pesa |

You never edit URLs — edit **one variable** in `backend/.env`:

```bash
# testing
DARAJA_ENV=sandbox

# live money
DARAJA_ENV=production
```

Restart the server after changing it: `npm start`.

---

## Step 1 — Run in DEMO mode (no Safaricom account needed)

`.env` ships with `DARAJA_MOCK=true`. The `/api/payments/prompt` endpoint simulates the
whole STK Push and auto-completes it ~12 s later with a fake receipt (`Q…`), so the app,
database and transaction history are 100% testable immediately.

## Step 2 — Real SANDBOX testing

1. Create a free account at **https://developer.safaricom.co.ke**.
2. Create an app → copy the **Consumer Key** and **Consumer Secret**.
3. Put them in `.env`:
   ```bash
   DARAJA_MOCK=false
   DARAJA_ENV=sandbox
   DARAJA_CONSUMER_KEY=<your key>
   DARAJA_CONSUMER_SECRET=<your secret>
   DARAJA_SHORTCODE=174379          # Lipa Na M-Pesa Online test shortcode
   DARAJA_PASSKEY=bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919
   ```
4. Safaricom must be able to reach your callback URL over **public HTTPS**.
   On a laptop: `ngrok http 3000` → copy the https URL and set:
   ```bash
   DARAJA_CALLBACK_URL=https://<your-ngrok-subdomain>.ngrok-free.app/api/payments/callback
   ```
5. Test phone numbers: use the sandbox test MSISDNs from the Daraja portal
   (e.g. `254708374149`). The STK prompt appears in the **Daraja sandbox simulator**
   (portal → “Simulate”), not on a physical phone.

## Step 3 — Go LIVE (production)

1. Complete Daraja **go-live** for your shortcode in the portal
   (portal → your app → “Go Live”; you need a registered Paybill/Till).
2. Update `.env`:
   ```bash
   DARAJA_MOCK=false
   DARAJA_ENV=production
   DARAJA_CONSUMER_KEY=<prod key>
   DARAJA_CONSUMER_SECRET=<prod secret>
   DARAJA_SHORTCODE=<your real Paybill/Till>
   DARAJA_PASSKEY=<your real passkey from Safaricom>
   DARAJA_CALLBACK_URL=https://<your-domain>/api/payments/callback
   ```
3. Host the backend somewhere with a permanent HTTPS URL (VPS + Caddy/Nginx,
   Render, Railway, etc. or `docker compose up -d`).

## Production checklist

- [ ] `DARAJA_MOCK=false` and `DARAJA_ENV=production`
- [ ] Real consumer key/secret, shortcode and passkey set
- [ ] Backend served over **HTTPS** with a stable public URL
- [ ] `JWT_SECRET` changed to a long random string
- [ ] Default passwords changed (`admin123`, `cashier123`) — use `PUT /api/auth/password`
- [ ] Real M-Pesa numbers start with 254 — the app normalises 07…/01… automatically
- [ ] Android `network_security_config.xml`: set `cleartextTrafficPermitted="false"`
- [ ] Database backed up (copy `backend/data/shop.db`), or hosted on a persistent volume

## If the callback never arrives

A payment that stays `PENDING` can be reconciled manually:
`POST /api/payments/query/:checkoutRequestId` (login required) — the backend asks
Daraja for the final status and updates the database. The app also times out
pending transactions after 3 minutes if the customer simply ignores the prompt.
