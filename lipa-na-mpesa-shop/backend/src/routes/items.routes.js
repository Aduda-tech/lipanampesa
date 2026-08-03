'use strict';
const express = require('express');
const multer = require('multer');
const XLSX = require('xlsx');
const db = require('../db');
const { authGuard, adminOnly } = require('../middleware');

const router = express.Router();
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 5 * 1024 * 1024 } });

/** GET /api/items?q=...&all=1 — catalog of THE CALLER'S SHOP only */
router.get('/', authGuard, (req, res) => {
  const q = String(req.query.q || '').trim();
  const showAll = req.query.all === '1' && req.user.role === 'admin';
  const cond = ['shop_id = ?'];
  const args = [req.user.shop_id];
  if (!showAll) cond.push('active = 1');
  if (q) {
    cond.push('(name LIKE ? OR category LIKE ? OR barcode LIKE ?)');
    args.push(`%${q}%`, `%${q}%`, `%${q}%`);
  }
  const sql = `SELECT * FROM items WHERE ${cond.join(' AND ')} ORDER BY category, name LIMIT 500`;
  res.json(db.prepare(sql).all(...args));
});

/** GET /api/items/barcode/:code — lookup by scanned barcode (own shop) */
router.get('/barcode/:code', authGuard, (req, res) => {
  const item = db
    .prepare('SELECT * FROM items WHERE shop_id = ? AND barcode = ? AND active = 1')
    .get(req.user.shop_id, String(req.params.code).trim());
  if (!item) return res.status(404).json({ error: `No item with barcode ${req.params.code}` });
  res.json(item);
});

/** POST /api/items (admin) */
router.post('/', authGuard, adminOnly, (req, res) => {
  const { name, category, barcode, unit_price, currency, description, tax_rate } = req.body || {};
  const price = Number(unit_price);
  const tax = Number(tax_rate || 0);
  if (!name || !Number.isFinite(price) || price < 0 || !Number.isFinite(tax) || tax < 0 || tax > 1) {
    return res.status(400).json({ error: 'name, a valid unit_price and tax_rate (0..1) are required' });
  }
  const info = db
    .prepare(
      'INSERT INTO items (shop_id, name, category, barcode, unit_price, currency, description, tax_rate) VALUES (?,?,?,?,?,?,?,?)'
    )
    .run(req.user.shop_id, String(name).trim(), category || null, barcode || null, price,
      (currency || 'KES').toUpperCase(), description || null, tax);
  res.status(201).json(db.prepare('SELECT * FROM items WHERE id = ?').get(info.lastInsertRowid));
});

/** PUT /api/items/:id (admin) */
router.put('/:id', authGuard, adminOnly, (req, res) => {
  const item = db.prepare('SELECT * FROM items WHERE id = ? AND shop_id = ?').get(req.params.id, req.user.shop_id);
  if (!item) return res.status(404).json({ error: 'Item not found' });
  const next = {
    name: req.body.name ?? item.name,
    category: req.body.category ?? item.category,
    barcode: req.body.barcode ?? item.barcode,
    unit_price: req.body.unit_price != null ? Number(req.body.unit_price) : item.unit_price,
    currency: (req.body.currency ?? item.currency).toUpperCase(),
    description: req.body.description ?? item.description,
    tax_rate: req.body.tax_rate != null ? Number(req.body.tax_rate) : item.tax_rate,
  };
  if (!Number.isFinite(next.unit_price) || next.unit_price < 0) {
    return res.status(400).json({ error: 'unit_price must be a non-negative number' });
  }
  if (!Number.isFinite(next.tax_rate) || next.tax_rate < 0 || next.tax_rate > 1) {
    return res.status(400).json({ error: 'tax_rate must be between 0 and 1' });
  }
  const run = db.transaction(() => {
    if (next.unit_price !== item.unit_price) {
      db.prepare(
        'INSERT INTO price_history (shop_id, item_id, old_price, new_price, changed_by) VALUES (?,?,?,?,?)'
      ).run(req.user.shop_id, item.id, item.unit_price, next.unit_price, req.user.username);
    }
    db.prepare(
      `UPDATE items SET name=?, category=?, barcode=?, unit_price=?, currency=?, description=?, tax_rate=?,
       updated_at=datetime('now') WHERE id=? AND shop_id=?`
    ).run(next.name, next.category, next.barcode, next.unit_price, next.currency,
      next.description, next.tax_rate, item.id, req.user.shop_id);
  });
  run();
  res.json(db.prepare('SELECT * FROM items WHERE id = ?').get(item.id));
});

/** DELETE /api/items/:id (admin) — soft delete */
router.delete('/:id', authGuard, adminOnly, (req, res) => {
  const r = db.prepare("UPDATE items SET active = 0, updated_at = datetime('now') WHERE id = ? AND shop_id = ?")
    .run(req.params.id, req.user.shop_id);
  if (r.changes === 0) return res.status(404).json({ error: 'Item not found' });
  res.json({ ok: true });
});

/** GET /api/items/:id/prices (admin) */
router.get('/:id/prices', authGuard, adminOnly, (req, res) => {
  res.json(
    db.prepare('SELECT * FROM price_history WHERE item_id = ? AND shop_id = ? ORDER BY changed_at DESC')
      .all(req.params.id, req.user.shop_id)
  );
});

/**
 * POST /api/items/import (admin) — CSV/XLSX bulk import into YOUR shop.
 * Columns: name, category, unit_price, currency, barcode, description, tax_rate.
 * Existing barcode (in this shop) → update.
 */
router.post('/import', authGuard, adminOnly, upload.single('file'), (req, res) => {
  if (!req.file) return res.status(400).json({ error: 'Attach file as multipart field "file"' });
  let rows;
  try {
    const wb = XLSX.read(req.file.buffer, { type: 'buffer' });
    rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { defval: '' });
  } catch (e) {
    return res.status(400).json({ error: 'Could not parse file: ' + e.message });
  }
  const shopId = req.user.shop_id;
  const norm = (r) => {
    const o = {};
    for (const k of Object.keys(r)) o[k.trim().toLowerCase()] = r[k];
    return o;
  };
  const findByBarcode = db.prepare('SELECT * FROM items WHERE shop_id = ? AND barcode = ?');
  const insert = db.prepare(
    'INSERT INTO items (shop_id, name, category, barcode, unit_price, currency, description, tax_rate) VALUES (?,?,?,?,?,?,?,?)'
  );
  const update = db.prepare(
    `UPDATE items SET name=?, category=?, unit_price=?, currency=?, description=?, tax_rate=?,
     updated_at=datetime('now') WHERE id=? AND shop_id=?`
  );
  const audit = db.prepare(
    'INSERT INTO price_history (shop_id, item_id, old_price, new_price, changed_by) VALUES (?,?,?,?,?)'
  );

  let inserted = 0, updated = 0, skipped = 0;
  const run = db.transaction(() => {
    for (const raw of rows) {
      const r = norm(raw);
      const name = String(r.name || '').trim();
      const price = Number(r.unit_price || r.price);
      if (!name || !Number.isFinite(price)) { skipped++; continue; }
      const barcode = String(r.barcode || '').trim() || null;
      const currency = String(r.currency || 'KES').trim().toUpperCase() || 'KES';
      const category = String(r.category || '').trim() || null;
      const description = String(r.description || '').trim() || null;
      const taxRate = Math.min(1, Math.max(0, Number(r.tax_rate || r.tax || 0) || 0));
      const existing = barcode ? findByBarcode.get(shopId, barcode) : null;
      if (existing) {
        if (existing.unit_price !== price) audit.run(shopId, existing.id, existing.unit_price, price, req.user.username);
        update.run(name, category, price, currency, description, taxRate, existing.id, shopId);
        updated++;
      } else {
        insert.run(shopId, name, category, barcode, price, currency, description, taxRate);
        inserted++;
      }
    }
  });
  run();
  res.json({ ok: true, inserted, updated, skipped, total_rows: rows.length, shop_id: shopId });
});

module.exports = router;
