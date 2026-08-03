'use strict';
const jwt = require('jsonwebtoken');
const config = require('./config');
const db = require('./db');

/**
 * Verifies the Bearer JWT, then loads the user AND their shop.
 * Attaches: req.user = { id, username, role, shop_id }, req.shop = { ...shop row }.
 * Everything downstream is tenant-scoped through these.
 */
function authGuard(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Missing Bearer token' });
  try {
    const payload = jwt.verify(token, config.jwtSecret);
    const user = db
      .prepare('SELECT id, username, role, shop_id FROM users WHERE id = ?')
      .get(payload.id);
    if (!user) return res.status(401).json({ error: 'Account no longer exists' });
    req.user = user;
    req.shop = db.prepare('SELECT * FROM shops WHERE id = ?').get(user.shop_id);
    if (!req.shop) return res.status(500).json({ error: 'Shop missing' });
    next();
  } catch {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}

/** Only shop admins may pass. */
function adminOnly(req, res, next) {
  if (!req.user || req.user.role !== 'admin') {
    return res.status(403).json({ error: 'Admin access required' });
  }
  next();
}

module.exports = { authGuard, adminOnly };
