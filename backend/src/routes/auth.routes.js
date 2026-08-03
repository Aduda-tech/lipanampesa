'use strict';
const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const db = require('../db');
const config = require('../config');
const { authGuard, adminOnly } = require('../middleware');

const router = express.Router();

/** POST /api/auth/login  { username, password } -> { token, user, shop } */
router.post('/login', (req, res) => {
  const { username, password } = req.body || {};
  if (!username || !password) {
    return res.status(400).json({ error: 'username and password are required' });
  }
  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(String(username).trim());
  if (!user || !bcrypt.compareSync(String(password), user.password_hash)) {
    return res.status(401).json({ error: 'Invalid username or password' });
  }
  const shop = db.prepare('SELECT id, name, use_mock, daraja_env FROM shops WHERE id = ?').get(user.shop_id);
  const payload = { id: user.id, username: user.username, role: user.role, shop_id: user.shop_id };
  const token = jwt.sign(payload, config.jwtSecret, { expiresIn: '12h' });
  res.json({
    token,
    user: { username: user.username, role: user.role, display_name: user.display_name },
    shop: shop
      ? { id: shop.id, name: shop.name, mode: shop.use_mock ? 'MOCK' : shop.daraja_env.toUpperCase() }
      : null,
  });
});

/** GET /api/auth/me — who am I? */
router.get('/me', authGuard, (req, res) => {
  res.json({ user: req.user, shop: { id: req.shop.id, name: req.shop.name } });
});

/** POST /api/auth/users  (admin) — create cashier/admin accounts IN YOUR OWN SHOP */
router.post('/users', authGuard, adminOnly, (req, res) => {
  const { username, password, role, display_name } = req.body || {};
  if (!username || !password || !['admin', 'cashier'].includes(role)) {
    return res.status(400).json({ error: 'username, password and role (admin|cashier) required' });
  }
  if (String(password).length < 6) {
    return res.status(400).json({ error: 'password must be 6+ chars' });
  }
  try {
    const info = db
      .prepare('INSERT INTO users (shop_id, username, password_hash, role, display_name) VALUES (?,?,?,?,?)')
      .run(req.user.shop_id, String(username).trim(), bcrypt.hashSync(String(password), 10),
        role, display_name || username);
    res.status(201).json({ id: info.lastInsertRowid, username, role, shop: req.shop.name });
  } catch (e) {
    res.status(409).json({ error: 'Username already exists' });
  }
});

/** GET /api/auth/users (admin) — staff list of your shop */
router.get('/users', authGuard, adminOnly, (req, res) => {
  res.json(
    db.prepare('SELECT id, username, role, display_name, created_at FROM users WHERE shop_id = ? ORDER BY username')
      .all(req.user.shop_id)
  );
});

/** PUT /api/auth/password — change own password */
router.put('/password', authGuard, (req, res) => {
  const { old_password, new_password } = req.body || {};
  if (!old_password || !new_password || String(new_password).length < 6) {
    return res.status(400).json({ error: 'old_password and new_password (min 6 chars) required' });
  }
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.user.id);
  if (!bcrypt.compareSync(String(old_password), user.password_hash)) {
    return res.status(401).json({ error: 'Old password is wrong' });
  }
  db.prepare('UPDATE users SET password_hash = ? WHERE id = ?').run(
    bcrypt.hashSync(String(new_password), 10),
    user.id
  );
  res.json({ ok: true });
});

module.exports = router;
