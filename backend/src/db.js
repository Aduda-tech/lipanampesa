'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Database = require('better-sqlite3');
const bcrypt = require('bcryptjs');
const config = require('./config');

fs.mkdirSync(path.dirname(config.dbFile), { recursive: true });
const db = new Database(config.dbFile);
db.pragma('journal_mode = WAL');

db.exec(`
-- Tenants: every shop is isolated — own catalog, users, transactions and Daraja keys.
CREATE TABLE IF NOT EXISTS shops (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  name            TEXT NOT NULL,
  shop_key        TEXT UNIQUE NOT NULL,          -- used in the shop's unique M-Pesa callback URL
  daraja_env      TEXT NOT NULL DEFAULT 'sandbox',
  business_type   TEXT NOT NULL DEFAULT 'paybill', -- paybill | till | till_store
  shortcode       TEXT,                          -- till number / paybill / HQ shortcode
  store_number    TEXT,                          -- for 'till_store': branch/store number (PartyB)
  operator_name   TEXT,                          -- operator/attendant shown on receipts (records only)
  consumer_key    TEXT,
  consumer_secret TEXT,
  passkey         TEXT,
  account_ref     TEXT,
  use_mock        INTEGER NOT NULL DEFAULT 1,    -- 1 = demo/simulated payments until keys are set
  created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  shop_id       INTEGER NOT NULL DEFAULT 1 REFERENCES shops(id),
  username      TEXT UNIQUE NOT NULL,            -- globally unique: all you need to log in
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL CHECK (role IN ('admin','cashier')),
  display_name  TEXT,
  created_at    TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS items (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  shop_id     INTEGER NOT NULL DEFAULT 1 REFERENCES shops(id),
  name        TEXT NOT NULL,
  category    TEXT,
  barcode     TEXT,
  unit_price  REAL NOT NULL CHECK (unit_price >= 0),
  currency    TEXT NOT NULL DEFAULT 'KES',
  description TEXT,
  tax_rate    REAL NOT NULL DEFAULT 0,
  active      INTEGER NOT NULL DEFAULT 1,
  created_at  TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_items_shop    ON items(shop_id);
CREATE INDEX IF NOT EXISTS idx_items_barcode ON items(shop_id, barcode);

CREATE TABLE IF NOT EXISTS transactions (
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  shop_id             INTEGER NOT NULL DEFAULT 1 REFERENCES shops(id),
  item_id             INTEGER,
  item_name           TEXT NOT NULL,
  unit_price          REAL NOT NULL,
  quantity            INTEGER NOT NULL DEFAULT 1,
  amount              REAL NOT NULL,
  tax_amount          REAL NOT NULL DEFAULT 0,
  items_json          TEXT,
  currency            TEXT NOT NULL DEFAULT 'KES',
  customer_phone      TEXT NOT NULL,
  account_reference   TEXT,
  merchant_request_id TEXT,
  checkout_request_id TEXT UNIQUE,
  mpesa_receipt       TEXT,
  result_code         INTEGER,
  result_desc         TEXT,
  status              TEXT NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','SUCCESS','FAILED','TIMEOUT')),
  cashier_username    TEXT,
  created_at          TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at          TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_txn_shop ON transactions(shop_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS idx_txn_receipt_uniq
  ON transactions(mpesa_receipt) WHERE mpesa_receipt IS NOT NULL;

CREATE TABLE IF NOT EXISTS price_history (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  shop_id    INTEGER NOT NULL DEFAULT 1,
  item_id    INTEGER NOT NULL,
  old_price  REAL NOT NULL,
  new_price  REAL NOT NULL,
  changed_by TEXT,
  changed_at TEXT NOT NULL DEFAULT (datetime('now'))
);
`);

/* -------- Migrations for databases created by v1.x -------- */
function ensureColumn(table, column, ddl) {
  const cols = db.prepare(`PRAGMA table_info(${table})`).all().map((c) => c.name);
  if (!cols.includes(column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${ddl}`);
    console.log(`[db] migrated: added ${table}.${column}`);
  }
}
// v1.1 -> v2.0 tenancy columns
ensureColumn('users', 'shop_id', 'shop_id INTEGER NOT NULL DEFAULT 1');
ensureColumn('items', 'shop_id', 'shop_id INTEGER NOT NULL DEFAULT 1');
ensureColumn('transactions', 'shop_id', 'shop_id INTEGER NOT NULL DEFAULT 1');
ensureColumn('price_history', 'shop_id', 'shop_id INTEGER NOT NULL DEFAULT 1');
// v1.0 -> v1.1 columns (in case someone jumps from the first release)
ensureColumn('items', 'tax_rate', 'tax_rate REAL NOT NULL DEFAULT 0');
ensureColumn('transactions', 'tax_amount', 'tax_amount REAL NOT NULL DEFAULT 0');
ensureColumn('transactions', 'items_json', 'items_json TEXT');
// v2.0 -> v2.1: friendly Daraja setup (till/store/operator)
ensureColumn('shops', 'business_type', "business_type TEXT NOT NULL DEFAULT 'paybill'");
ensureColumn('shops', 'store_number', 'store_number TEXT');
ensureColumn('shops', 'operator_name', 'operator_name TEXT');

/* -------- Always ensure one demo shop exists (id 1 receives any legacy data) -------- */
let demoShop = db.prepare('SELECT * FROM shops WHERE id = 1').get();
if (!demoShop) {
  db.prepare(
    "INSERT INTO shops (id, name, shop_key, use_mock) VALUES (1, 'Demo Shop', ?, 1)"
  ).run(crypto.randomBytes(9).toString('hex'));
  demoShop = db.prepare('SELECT * FROM shops WHERE id = 1').get();
  console.log('[db] Created demo shop (id=1)');
}

/* ---------------- Default users (change passwords after first login!) ---------------- */
const userCount = db.prepare('SELECT COUNT(*) AS c FROM users').get().c;
if (userCount === 0) {
  const ins = db.prepare(
    'INSERT INTO users (shop_id, username, password_hash, role, display_name) VALUES (1,?,?,?,?)'
  );
  ins.run('admin', bcrypt.hashSync('admin123', 10), 'admin', 'Shop Admin (Demo Shop)');
  ins.run('cashier', bcrypt.hashSync('cashier123', 10), 'cashier', 'Sales Rep (Demo Shop)');
  console.log('[db] Seeded default users: admin/admin123, cashier/cashier123');
}

/* ---------------- Sample catalog for the demo shop ---------------- */
const itemCount = db.prepare('SELECT COUNT(*) AS c FROM items').get().c;
if (itemCount === 0) {
  const cur = config.defaultCurrency;
  const catalog = [
    ['A4 Ruled Notebook (200 pages)', 'Stationery', '100001', 150, 'Hard cover ruled notebook'],
    ['A4 Exercise Book 80 GSM', 'Stationery', '100002', 120, '80gsm exercise book'],
    ['Ballpoint Pens (pack of 10)', 'Stationery', '100003', 250, 'Black & blue ink'],
    ['Sticky Notes (assorted)', 'Stationery', '100004', 120, 'Pack of 4 pads'],
    ['A4 Printing Paper (ream)', 'Stationery', '100005', 550, '500 sheets, 80gsm'],
    ['USB-C Fast Charger 25W', 'Phone Accessories', '100006', 1500, 'Type-C wall charger'],
    ['USB-C Charging Cable', 'Phone Accessories', '100007', 400, '1m braided cable'],
    ['Wired Earphones 3.5mm', 'Phone Accessories', '100008', 350, 'With microphone'],
    ['Bluetooth Earbuds', 'Phone Accessories', '100009', 1800, 'TWS earbuds with case'],
    ['AA Batteries (4-pack)', 'Batteries', '100010', 320, 'Alkaline AA x4'],
    ['Phone Battery Replacement', 'Services', '100011', 2500, 'Labour + battery'],
    ['Laptop Screen Replacement', 'Services', '100012', 8500, '15.6" standard screen'],
    ['Windows Installation & Setup', 'Services', '100013', 1000, 'OS install + drivers'],
    ['Virus / Malware Removal', 'Services', '100014', 800, 'Full cleanup'],
    ['Data Recovery (per session)', 'Services', '100015', 3000, 'From damaged drives'],
    ['Printer Service & Repair', 'Services', '100016', 1500, 'Diagnosis + service'],
  ];
  const ins = db.prepare(
    'INSERT INTO items (shop_id, name, category, barcode, unit_price, currency, description) VALUES (1,?,?,?,?,?,?)'
  );
  const seedMany = db.transaction((rows) => {
    for (const [name, category, barcode, price, desc] of rows) {
      ins.run(name, category, barcode, price, cur, desc);
    }
  });
  seedMany(catalog);
  console.log(`[db] Seeded ${catalog.length} sample catalog items (demo shop)`);
}

module.exports = db;
