'use strict';
const express = require('express');
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const rateLimit = require('express-rate-limit');
const db = require('../db');
const config = require('../config');
const { authGuard, adminOnly } = require('../middleware');

const router = express.Router();

const registerLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many registrations from this address — try later.' },
});

/** Public base URL of this server (for building the shop's callback URL). */
function publicBase(req) {
  if (config.publicUrl) return config.publicUrl.replace(/\/$/, '');
  return `${req.protocol}://${req.get('host')}`;
}

function callbackUrl(req, shop) {
  return `${publicBase(req)}/api/payments/callback/${shop.shop_key}`;
}

/**
 * POST /api/shops/register  (public)
 * { shop_name, admin_username, admin_password, display_name? }
 * Creates a tenant (shop) + its admin account. Shop starts in DEMO (mock) mode;
 * the admin later enters their own Daraja keys in Settings to go live.
 */
router.post('/register', registerLimiter, (req, res) => {
  const { shop_name, admin_username, admin_password, display_name } = req.body || {};
  const shopName = String(shop_name || '').trim();
  const username = String(admin_username || '').trim();
  const password = String(admin_password || '');
  if (shopName.length < 2) return res.status(400).json({ error: 'shop_name is required (2+ chars)' });
  if (!/^[a-zA-Z0-9_.-]{3,}$/.test(username)) {
    return res.status(400).json({ error: 'admin_username must be 3+ chars (letters, numbers, _ - .)' });
  }
  if (password.length < 6) return res.status(400).json({ error: 'admin_password must be 6+ chars' });
  if (db.prepare('SELECT id FROM users WHERE username = ?').get(username)) {
    return res.status(409).json({ error: `Username "${username}" is taken — pick another` });
  }

  const key = crypto.randomBytes(9).toString('hex');
  const create = db.transaction(() => {
    const shopInfo = db
      .prepare('INSERT INTO shops (name, shop_key) VALUES (?, ?)')
      .run(shopName, key);
    db.prepare(
      'INSERT INTO users (shop_id, username, password_hash, role, display_name) VALUES (?,?,?,?,?)'
    ).run(shopInfo.lastInsertRowid, username, bcrypt.hashSync(password, 10), 'admin',
      display_name || `${shopName} Admin`);
    return shopInfo.lastInsertRowid;
  });
  const shopId = create();
  const shop = db.prepare('SELECT * FROM shops WHERE id = ?').get(shopId);

  res.status(201).json({
    ok: true,
    shop_id: shopId,
    shop_name: shopName,
    admin_username: username,
    mode: 'MOCK (demo payments until you add Daraja keys)',
    callback_url: callbackUrl(req, shop),
    next_steps: [
      'Log in on the app with your new admin account and add cashiers (Admin → Add user).',
      'Stock the catalog manually, via barcode, or Import CSV/Excel.',
      `When ready for real money: put your Daraja keys in Settings (PUT /api/shops/me) and paste this callback URL in the Daraja portal: ${callbackUrl(req, shop)}`,
    ],
  });
});

/** GET /api/shops/me (admin) — shop profile incl. Daraja settings (secrets masked). */
router.get('/me', authGuard, adminOnly, (req, res) => {
  const s = req.shop;
  const mask = (v) => (v ? '••••••' + String(v).slice(-4) : null);
  const hasKeys = !!(s.consumer_key && s.consumer_secret && s.shortcode && s.passkey);
  const type = s.business_type || 'paybill';
  const targetLabel =
    type === 'till_store'
      ? `Store ${s.store_number || '?'} under HQ shortcode ${s.shortcode || '?'}`
      : type === 'till'
        ? `Buy Goods till ${s.shortcode || '?'}`
        : `Paybill ${s.shortcode || '?'}`;
  res.json({
    id: s.id,
    name: s.name,
    use_mock: s.use_mock === 1,
    daraja_env: s.daraja_env,
    business_type: type,
    shortcode: s.shortcode,
    store_number: s.store_number,
    operator_name: s.operator_name,
    account_ref: s.account_ref,
    consumer_key: s.consumer_key,
    consumer_secret: mask(s.consumer_secret),
    passkey: mask(s.passkey),
    keys_complete: hasKeys,
    charge_target: targetLabel,
    callback_url: callbackUrl(req, s),
    stats: {
      users: db.prepare('SELECT COUNT(*) c FROM users WHERE shop_id = ?').get(s.id).c,
      items: db.prepare('SELECT COUNT(*) c FROM items WHERE shop_id = ? AND active = 1').get(s.id).c,
      transactions: db.prepare('SELECT COUNT(*) c FROM transactions WHERE shop_id = ?').get(s.id).c,
    },
  });
});

/** PUT /api/shops/me (admin) — update name / till details / Daraja keys / mock toggle. */
router.put('/me', authGuard, adminOnly, (req, res) => {
  const s = req.shop;
  const b = req.body || {};
  const next = {
    name: b.name ?? s.name,
    daraja_env: b.daraja_env ? String(b.daraja_env).toLowerCase() : s.daraja_env,
    business_type: b.business_type ? String(b.business_type).toLowerCase() : (s.business_type || 'paybill'),
    shortcode: b.shortcode ?? s.shortcode,
    store_number: b.store_number ?? s.store_number,
    operator_name: b.operator_name ?? s.operator_name,
    consumer_key: b.consumer_key ?? s.consumer_key,
    consumer_secret: b.consumer_secret ?? s.consumer_secret,
    passkey: b.passkey ?? s.passkey,
    account_ref: b.account_ref ?? s.account_ref,
    use_mock: b.use_mock != null ? (b.use_mock ? 1 : 0) : s.use_mock,
  };
  if (!['sandbox', 'production'].includes(next.daraja_env)) {
    return res.status(400).json({ error: 'daraja_env must be sandbox or production' });
  }
  if (!['paybill', 'till', 'till_store'].includes(next.business_type)) {
    return res.status(400).json({ error: 'business_type must be paybill, till or till_store' });
  }
  // Masked secrets sent back unchanged would clobber real keys — block them.
  for (const k of ['consumer_secret', 'passkey']) {
    if (typeof next[k] === 'string' && next[k].includes('•')) next[k] = s[k];
  }
  if (next.use_mock === 0) {
    const missing = [];
    if (!next.consumer_key) missing.push('consumer_key');
    if (!next.consumer_secret) missing.push('consumer_secret');
    if (!next.shortcode) missing.push('shortcode (till/paybill number)');
    if (!next.passkey) missing.push('passkey');
    if (next.business_type === 'till_store' && !next.store_number) missing.push('store_number');
    if (missing.length) {
      return res.status(400).json({
        error: `To activate real payments (use_mock=false) first set: ${missing.join(', ')}`,
        callback_url: callbackUrl(req, s),
      });
    }
  }
  db.prepare(
    `UPDATE shops SET name=?, daraja_env=?, business_type=?, shortcode=?, store_number=?,
     operator_name=?, consumer_key=?, consumer_secret=?, passkey=?, account_ref=?, use_mock=? WHERE id=?`
  ).run(next.name, next.daraja_env, next.business_type, next.shortcode, next.store_number,
    next.operator_name, next.consumer_key, next.consumer_secret, next.passkey,
    next.account_ref, next.use_mock, s.id);
  const updated = db.prepare('SELECT * FROM shops WHERE id = ?').get(s.id);
  res.json({
    ok: true,
    name: updated.name,
    use_mock: updated.use_mock === 1,
    daraja_env: updated.daraja_env,
    business_type: updated.business_type,
    callback_url: callbackUrl(req, updated),
    message: updated.use_mock === 1
      ? 'Saved. Shop stays in DEMO mode until you tick “activate real payments”.'
      : 'Saved — real M-Pesa prompts are now ACTIVE for this shop.',
  });
});

/** POST /api/shops/me/regenerate-key (admin) — new callback URL (invalidates the old one). */
router.post('/me/regenerate-key', authGuard, adminOnly, (req, res) => {
  const key = crypto.randomBytes(9).toString('hex');
  db.prepare('UPDATE shops SET shop_key = ? WHERE id = ?').run(key, req.shop.id);
  const updated = db.prepare('SELECT * FROM shops WHERE id = ?').get(req.shop.id);
  res.json({ ok: true, callback_url: callbackUrl(req, updated) });
});

module.exports = router;
