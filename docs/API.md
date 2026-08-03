# API reference (v1.1)

Base URL: `http://localhost:3000` · Auth: `Authorization: Bearer <JWT>` unless noted.
Content type: `application/json`.

## Auth

| Method & path | Role | Description |
|---|---|---|
| `POST /api/auth/login` | public | `{username, password}` → `{token, user}` (rate-limited: 30/15 min) |
| `GET /api/auth/me` | any | Current user from token |
| `POST /api/auth/users` | admin | Create user: `{username, password, role: admin\|cashier, display_name?}` |
| `PUT /api/auth/password` | any | Change own password: `{old_password, new_password}` |

## Catalog

| Method & path | Role | Description |
|---|---|---|
| `GET /api/items?q=&all=` | any | List active items (`all=1` includes inactive, admin only) |
| `GET /api/items/barcode/:code` | any | Single item by scanned barcode |
| `POST /api/items` | admin | Create: `{name, category?, barcode?, unit_price, currency?, description?, tax_rate?}` — `tax_rate` 0..1, e.g. `0.16` = 16% VAT added on top |
| `PUT /api/items/:id` | admin | Update — price changes are audited in `price_history` |
| `DELETE /api/items/:id` | admin | Soft-delete (`active=0`) so history is preserved |
| `GET /api/items/:id/prices` | admin | Price-change audit trail |
| `POST /api/items/import` | admin | Multipart `file` = CSV/XLSX. Columns: `name, category, unit_price, currency, barcode, description, tax_rate`. Barcode match → update. |

## Payments (Daraja)

| Method & path | Role | Description |
|---|---|---|
| `POST /api/payments/prompt` | any | **Cart:** `{items: [{item_id, quantity}, ...], customer_phone, account_reference?}`. Legacy single line `{item_id, quantity, customer_phone}` still works. Inserts one PENDING transaction for the whole bill (amount = subtotal + tax) and sends ONE STK Push (rate-limited: 60/min) |
| `POST /api/payments/callback` | **public** | Safaricom calls this. Records `mpesa_receipt` + result code, marks SUCCESS/FAILED |
| `GET /api/payments/status/:checkoutRequestId` | any | Poll → `{status, mpesa_receipt, amount, tax_amount, items: [...], …}` |
| `POST /api/payments/query/:checkoutRequestId` | any | Manual Daraja STK query fallback if a callback is missed |

Prompt response:

```json
{
  "transaction_id": 7,
  "checkout_request_id": "ws_CO_...",
  "status": "PENDING",
  "items": [{ "item_id": 6, "name": "USB-C Fast Charger 25W", "unit_price": 1500, "quantity": 2, "tax_rate": 0.16, "line_total": 3480 }],
  "subtotal": 3000, "tax_amount": 480, "amount": 5280,
  "currency": "KES", "mode": "SANDBOX", "message": "STK push sent …"
}
```

## Transactions

| Method & path | Role | Description |
|---|---|---|
| `GET /api/transactions?status=&q=&from=&to=&limit=` | any | Newest first, optional filters (dates `YYYY-MM-DD`) |
| `GET /api/transactions/summary?from=&to=` | any | Totals incl. `tax_collected` (defaults to today) |
| `GET /api/transactions/export.csv` | admin | Full CSV export (includes `tax_amount`) |
| `GET /api/transactions/:id` | any | Single record incl. parsed `items` cart snapshot |

## Misc

| Method & path | Role | Description |
|---|---|---|
| `GET /health` | public | `{ok: true}` |
| `GET /api/config` | public | `{version, default_currency, currencies, mode}` (no secrets) |

## Example session

```bash
TOKEN=$(curl -s -X POST localhost:3000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"cashier","password":"cashier123"}' | jq -r .token)

curl -s localhost:3000/api/items -H "Authorization: Bearer $TOKEN" | jq '.[0]'

# Multi-item cart checkout — one STK push for the whole bill
curl -s -X POST localhost:3000/api/payments/prompt \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"item_id":6,"quantity":2},{"item_id":9,"quantity":1}],"customer_phone":"0712345678"}' | jq

sleep 15   # give the customer (or the mock) time to pay
curl -s localhost:3000/api/payments/status/<checkout_request_id> \
  -H "Authorization: Bearer $TOKEN" | jq '{status, mpesa_receipt, amount, tax_amount}'
```
