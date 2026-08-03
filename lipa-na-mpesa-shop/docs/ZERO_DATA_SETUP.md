# Zero-bundle / no-typing setup guide

> **Goal:** run the shop on Safaricom phones with **no data bundles** and **no server
> configuration on the phones**. Here is exactly what needs internet and what doesn't.

## The one thing to understand

| Link in the chain | Needs internet? |
|---|---|
| **Customer's phone** (receives & approves the M-Pesa prompt) | ❌ **Never.** STK Push travels over Safaricom's SIM/GSM channel — no bundles, works on kabambe phones too |
| **Cashier's phone → shop server** | ❌ Not with this setup — it runs over the shop's own Wi-Fi / hotspot (free local traffic). **Auto-detected, zero typing** |
| **Shop server → Daraja API** (and Safaricom's callback back) | ✅ Yes — the **server machine** needs *some* internet (any small bundle via tethering, office fiber, Faiba…). Safaricom does not zero-rate Daraja API calls |
| **Everything in DEMO mode** (`DARAJA_MOCK=true`) | ❌ Nothing at all — full training/testing without a single MB spent |

You cannot reach `api.safaricom.co.ke` without an internet path on the server — that is a
Safaricom constraint, not an app one. This architecture minimises it to exactly one device.

## The recommended shop setup (5 minutes)

```
        ┌──────────────────┐  hotspot/Wi-Fi (no bundles needed)
Cashier phone 1 ──┐          │
Cashier phone 2 ──┤──────────┤   ┌───────────────┐        internet        ┌──────────────┐
Cashier phone 3 ──┘          ├──▶│  Shop server  │────(tiny bundle/fiber)─▶│  Safaricom   │
                             │   │ (PC/laptop/   │◀──────── Daraja callback ─│  (Daraja)  │
                             └──▶│  router PC/Rpi│                           └──────────────┘
                                 └───────────────┘
```

1. **Shop server** = any laptop/PC (or Raspberry Pi) in the shop. It connects to the internet
   however you like (tether a Safaricom line with a small bundle, or office internet), and
   shares its connection through its own hotspot **or** an ordinary Wi-Fi router.
   - Windows: Settings → Network → **Mobile hotspot** → On
   - Ubuntu: Settings → Wi-Fi → Turn On Hotspot
2. Start the backend on the server: `cd backend && npm ci && cp .env.example .env && npm start`.
   The console prints the shop URLs **plus a setup QR code**, and begins advertising itself
   on the LAN (`_mpesa-shop._tcp.local`).
3. For real M-Pesa, expose the callback once with `ngrok http 3000` and put the ngrok URL in
   `.env` (or use a proper public HTTPS host — see `docs/SANDBOX_VS_PRODUCTION.md`).

## Cashier onboarding (per phone, ~1 minute, zero bundles)

1. Join the shop Wi-Fi/hotspot (the one the server is on).
2. Open the browser: `http://<server-ip>:3000/install` — tap **Download app**, install.
3. Open **Lipa na M-Pesa Shop**. The login screen says **"🔎 Searching…"** then
   **"✅ Server found: http://192.168.x.x:3000/"** — the URL fills itself in.
   - If auto-detect doesn't fire (some phone hotspots isolate clients), tap **SCAN SETUP QR**
     and scan either the QR printed in the server console or the one on `/install`.
   - Last resort: type the URL printed next to the QR.
4. Log in (`cashier` / `cashier123`). Done — from now on the app remembers everything.

## If auto-detect fails

Android NSD (like all mDNS) needs the network to allow device-to-device traffic:

- Some **phone** hotspots isolate clients — use the **Windows/Ubuntu hotspot** or a cheap
  Wi-Fi router instead (a router doesn't even need an internet cable plugged in for LAN use).
- Corporate/public Wi-Fi with "AP isolation" won't work for the same reason.
- The app always falls back to **QR scan** or **manual URL**, and discovery can be re-run
  with the **DETECT** button.

## Daily reality with zero bundles on the till phone

- Selling, catalog, barcodes, transactions history, receipts: **all LAN — free**.
- The M-Pesa prompt still reaches the customer instantly (server → Daraja → SIM channel).
- The only data consumed anywhere is the server's Daraja traffic: roughly **1–2 KB per sale** —
  even the smallest bundle lasts months for the whole shop.
