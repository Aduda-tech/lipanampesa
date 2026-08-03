# User guide — shop staff (v1.1)

## Roles

| Role | Logs in with | Can do |
|---|---|---|
| **Admin** | username `admin` (default password `admin123`) | Everything cashiers can do **plus**: add/edit items, change prices & tax rates (audited), bulk import CSV/Excel, **create user accounts**, export transactions |
| **Cashier / sales rep** | username `cashier` (default password `cashier123`) | Sell: build a cart, customer phone → STK push; scan barcodes; share receipts; view transactions |

The role is decided by the **username** entered on the login screen.

## Making a sale (cashier)

1. Log in → you land on the sale screen (today's totals shown at the top).
2. Tap **Select item / service** (searchable dropdown) — or tap the camera icon to
   **scan the product barcode**. Enter the **quantity**.
3. Tap **ADD TO CART**. Repeat for more items — one M-Pesa prompt will cover the whole bill.
   Tap any cart line to remove it.
4. Enter the customer's **phone number** (`07…`, `01…` or `2547…` all work).
5. Tap **PROMPT PAYMENT**. The customer's phone shows the M-Pesa prompt.
6. Wait a few seconds while they enter their PIN. The screen turns green:
   **“✔ Payment received — M-Pesa receipt: QXXXXXXX”** with a full itemised receipt.
7. Tap **SHARE** to send the receipt to the customer via WhatsApp/SMS, or **NEW SALE**
   for the next customer. If they cancel, the screen shows the failure reason.

> Tip: skip step 3 for single-item sales — just select the item, phone, and PROMPT directly.

## Menu (top-right)

- **Transactions** — every attempt; tap a row for the full breakdown (cart lines, tax,
  receipt, cashier). Pull down to refresh.
- **Language** — switch between **English** and **Kiswahili** on the fly.
- **Currency** — change the display currency (KES default; USD/EUR/GBP/TZS/UGX/RWF).
  Note: M-Pesa always charges **KES**; the switcher only changes display formatting.

## Admin tasks

- **Edit prices / tax**: tap any item, change price or **tax rate** (e.g. `0.16` = 16% VAT
  added on top at checkout), **Save**. Price changes go to a `price_history` audit table.
- **Add item**: *Add item* → name, category, optional barcode, price, tax rate.
- **Import a price list**: *Import CSV / Excel* → columns
  `name,category,unit_price,currency,barcode,description,tax_rate`
  (see `samples/items-import-template.csv`). Existing barcodes get **updated** (no duplicates).
- **Add user**: *Add user* → username, password, Cashier/Admin radio. They can log in immediately.
- **Export the books**: `GET /api/transactions/export.csv` for accounting / reconciliation
  against M-Pesa statements (includes `tax_amount` per sale).

## Offline behaviour

If the server drops, the item dropdown keeps working from the last downloaded catalog
(“Offline catalog” toast). Prompts still need the server — they'll show a clean error.

## Troubleshooting

| Symptom | Fix |
|---|---|
| “Network error” at login | Check the **server URL** field; phone and backend must share a network; backend running? |
| Item list is empty | Backend unreachable (offline cache will kick in after first successful sync), or log in again |
| Stuck on “waiting for customer” | Customer ignored/expired the prompt → times out after ~3 min; `/api/payments/query` reconciles |
| “Invalid Safaricom phone number” | Use 07XXXXXXXX / 01XXXXXXXX / 2547XXXXXXXX |
| “Too many login attempts” | Rate limit — wait ~15 minutes (anti brute-force protection) |
