# Kiosk mode — completely server-free operation

The app has **two modes**, chosen at the login screen. Kiosk mode removes the backend
entirely: **no server, no hosting, no Render** — the phone/tablet talks to Safaricom
directly over mobile data.

| | **Platform mode** (cloud backend) | **Kiosk mode** (server-free) |
|---|---|---|
| What runs the system | One backend on Render/Railway (deploy once) | Nothing — just this APK |
| Daraja keys live | On the server (cashiers never see them) | **On this device only** |
| Works from | Any phone, anywhere | This one phone/tablet |
| Multi-device / remote admin (Nairobi vs shop) | ✅ Yes | ❌ No — single device |
| **M-Pesa receipt number auto-captured** | ✅ Yes (Daraja callback webhook) | ❌ **No** — confirmation comes from STK Query, which Safaricom designed NOT to return the receipt code; use the customer's SMS/your M-Pesa message for it |
| Payment confirmation | Callback + polling (instant) | STK Query poll every 5 s (~5–15 s) |
| Catalog / transactions | Central DB, shared, exportable | On-device only (≤200 recent sales kept) |
| Good for | Real multi-shop SaaS | One counter device, pop-up shops, demos, areas where you can't/won't host |

## Why receipts need a server (the one unavoidable thing)

Safaricom delivers `MpesaReceiptNumber` **only** through the `CallBackURL` in the STK Push —
a public HTTPS address they POST to. With zero server there is nowhere for them to POST,
so kiosk mode confirms *"*successfully processed*" via the same STK Query the backend uses
as a fallback, but the **receipt code itself is a server-mode feature**. This is a
Safaricom platform constraint; every M-Pesa integration on Earth works this way.

## Setting up kiosk mode (2 minutes, once per device)

1. Login screen → **NO SERVER? USE SINGLE-DEVICE KIOSK MODE**.
2. Tap **M-PESA SETTINGS**, enter:
   - Business type (Paybill / Buy Goods till / Till **with stores** — type your **store number**, it becomes PartyB)
   - **Till / Paybill number**, **consumer key + secret + passkey** (from developer.safaricom.co.ke — same portal step as server mode)
   - Sandbox or Production → **Save**. Keys never leave the device.
3. Sell: pick item (or **Add item**), quantity, customer phone → **PROMPT PAYMENT** →
   the customer enters their PIN on their phone (no bundles needed for them, as always) →
   the device polls Daraja until confirmed (~5–15 s) → sale saved in **HISTORY**.
4. Share the confirmation text via WhatsApp/SMS. For the official receipt number, use the
   M-Pesa confirmation SMS on the **customer's phone** or your till's line.

## Security notes for kiosk mode

- Anyone with the device + app access effectively has your Daraja API keys → protect the
  device with a lock screen, keep it behind the counter, and use a **dedicated staff app**
  user profile if possible.
- Lost device = regenerate your Daraja app credentials in the portal.
- Do NOT distribute kiosk-configured devices to staff you wouldn't trust with the keys —
  that's exactly what platform mode was built to solve.
- Sales history lives only on this device; clear it only intentionally (app data clear wipes it).
