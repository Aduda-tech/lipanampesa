'use strict';
const express = require('express');
const db = require('../db');
const { authGuard, adminOnly } = require('../middleware');

const router = express.Router();

/** GET /api/transactions?status=&q=&from=&to=&limit= — YOUR shop's transactions only */
router.get('/', authGuard, (req, res) => {
  const cond = ['shop_id = ?'];
  const args = [req.user.shop_id];
  if (req.query.status) {
    cond.push('status = ?');
    args.push(String(req.query.status).toUpperCase());
  }
  if (req.query.q) {
    cond.push('(mpesa_receipt LIKE ? OR customer_phone LIKE ? OR item_name LIKE ?)');
    const like = `%${req.query.q}%`;
    args.push(like, like, like);
  }
  if (req.query.from) {
    cond.push('date(created_at) >= date(?)');
    args.push(String(req.query.from));
  }
  if (req.query.to) {
    cond.push('date(created_at) <= date(?)');
    args.push(String(req.query.to));
  }
  const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 300, 1), 1000);
  const sql = `SELECT * FROM transactions WHERE ${cond.join(' AND ')} ORDER BY created_at DESC, id DESC LIMIT ?`;
  args.push(limit);
  res.json(db.prepare(sql).all(...args));
});

/** GET /api/transactions/summary?from=&to= */
router.get('/summary', authGuard, (req, res) => {
  const from = req.query.from ? String(req.query.from) : new Date().toISOString().slice(0, 10);
  const to = req.query.to ? String(req.query.to) : from;
  const row = db
    .prepare(
      `SELECT
         SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) AS success_count,
         SUM(CASE WHEN status='SUCCESS' THEN amount ELSE 0 END) AS success_amount,
         SUM(CASE WHEN status='SUCCESS' THEN tax_amount ELSE 0 END) AS tax_collected,
         SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END) AS pending_count,
         SUM(CASE WHEN status IN ('FAILED','TIMEOUT') THEN 1 ELSE 0 END) AS failed_count
       FROM transactions
       WHERE shop_id = ? AND date(created_at) BETWEEN date(?) AND date(?)`
    )
    .get(req.user.shop_id, from, to);
  res.json({ shop: req.shop.name, date: from === to ? from : `${from}..${to}`, ...row });
});

/** GET /api/transactions/export.csv (admin) */
router.get('/export.csv', authGuard, adminOnly, (req, res) => {
  const rows = db.prepare('SELECT * FROM transactions WHERE shop_id = ? ORDER BY id').all(req.user.shop_id);
  const cols = [
    'id', 'created_at', 'item_name', 'quantity', 'amount', 'tax_amount', 'currency', 'customer_phone',
    'mpesa_receipt', 'status', 'result_desc', 'cashier_username', 'checkout_request_id',
  ];
  const esc = (v) => {
    const s = v == null ? '' : String(v);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  const csv = [cols.join(','), ...rows.map((r) => cols.map((c) => esc(r[c])).join(','))].join('\n');
  res.setHeader('Content-Type', 'text/csv');
  res.setHeader('Content-Disposition', 'attachment; filename="transactions.csv"');
  res.send(csv);
});

/** GET /api/transactions/:id */
router.get('/:id', authGuard, (req, res) => {
  const tx = db.prepare('SELECT * FROM transactions WHERE id = ? AND shop_id = ?')
    .get(req.params.id, req.user.shop_id);
  if (!tx) return res.status(404).json({ error: 'Transaction not found' });
  res.json({ ...tx, items: JSON.parse(tx.items_json || '[]') });
});

module.exports = router;
