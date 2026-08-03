# Daraja setup for a shop admin — what you type, what the system derives

The goal: **PROMPT works for your shop after filling one small settings form**
(Admin screen → **M-Pesa / Daraja settings**). Here is exactly what goes where, and —
importantly — what *cannot* be automated because Safaricom owns that part.

## What the admin types (once)

| Field | Example | What the server does with it |
|---|---|---|
| **Business type** | Paybill / Buy Goods till / Buy Goods till **with stores** | Automatically picks the STK `TransactionType` (`CustomerPayBillOnline` vs `CustomerBuyGoodsOnline`) |
| **Till / Paybill number** | `5551234` | Becomes `BusinessShortCode` (and `PartyB`, except store setups) |
| **Store number** *(only for till networks with stores)* | `0001` | Becomes **`PartyB`** — money settles to that store's till while `BusinessShortCode` stays the HQ shortcode |
| **Operator name** *(optional)* | `Wanjiru K.` | Records/receipt attribution. **Safaricom's STK API does not use this field** — we keep it for your records and as an account-reference fallback |
| **Account reference** *(paybills)* | `INV2026` | Shown on the customer's phone (required for paybills) |
| **Consumer key + secret** | from portal | OAuth — identifies *your* Daraja app (**always required, cannot be avoided**) |
| **Lipa na M-Pesa passkey** | from portal | Encrypts the STK request password (**always required**) |
| **Environment** | sandbox / production | Chooses `sandbox.safaricom.co.ke` vs `api.safaricom.co.ke` |
| ☑ **Activate real payments** | tick | Flips the shop out of demo mode — only allowed when all of the above is present (server validates) |

After saving: every prompt from that shop uses these settings automatically. **Nothing is
ever configured on the cashiers' phones.**

## The one part only YOU can do on Safaricom's portal

Safaricom will not let any app (ours or anyone's) call Daraja with just a till number —
it requires API credentials tied to an application on **https://developer.safaricom.co.ke**:

1. Log in with the M-Pesa org account → **create an app** → copy the
   **consumer key + consumer secret**.
2. **Sandbox testing:** use shortcode `174379` and the well-known sandbox passkey
   (already in `backend/.env.example`).
3. **Production ("go-live"):** complete go-live for your shortcode in the portal and copy
   the **production Lipa na M-Pesa passkey** for your shortcode. Multiple stores?
   Go live with the **head-office shortcode** and your **store numbers** come from the
   M-Pesa org portal / Safaricom onboarding.
4. Paste the shop's **callback URL** (shown at the bottom of the settings form and after
   registration) into the portal's passkey/callback settings.

That's the whole manual part — a one-time, ~10-minute task per shop. Everything else
(transaction type, PartyB, password generation, timestamps, phone normalization,
receipt matching, timeouts) is handled by the server.

## Two ways a shop can receive money

| | A) Own till/paybill (recommended) | B) Platform line (instant start) |
|---|---|---|
| Money settles to | **The shop's own M-Pesa line** | The platform operator's line |
| What the admin enters | The full form above | Nothing — the platform's default keys apply |
| Trust/settlement | None needed | Platform forwards collections (mutual agreement) |

Option B exists so brand-new shops can try **real sandbox STK pushes** on day one using
the platform keys (set once via `DARAJA_*` env vars). Shops should switch to option A
before handling real customer money.

## Validation & safety nets

- Leaving demo mode without complete keys → server **refuses** with a list of what's missing.
- Store-based till without a store number → refused.
- Masked secrets shown in the form (`••••••ab12`) are never written back over real keys.
- Callback URLs are unique per shop and **rotatable** (Settings → ask your platform admin,
  or `POST /api/shops/me/regenerate-key`).
