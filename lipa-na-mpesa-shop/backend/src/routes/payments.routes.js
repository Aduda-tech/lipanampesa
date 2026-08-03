'use strict';
const express = require('express');
const db = require('../db');
const config = require('../config');
const daraja = require('../daraja');
const { authGuard } = require('../middleware');

const router = express.Router();

const markResult = db.prepare(`
  UPDATE transactions SET status=?, mpesa_receipt=COALESCE(?, mpesa_receipt),
  result_code=?, result_desc=?, updated_at=datetime('now')
  WHERE checkout_request_id=? AND shop_id=? AND status='PENDING'
`);
const touchTimeout = db.prepare(`
  UPDATE transactions SET status='TIMEOUT', updated_at=datetime('now')
  WHERE status='PENDING' AND created_at < datetime('now','-3 minutes')
`);
const INSERT_TXN = `
  INSERT INTO transactions (shop_id, item_id, item_name, unit_price, quantity, amount, tax_amount,
    items_json, currency, customer_phone, account_reference, merchant_request_id, checkout_request_id,
    status, cashier_username)
  VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING', ?)
`;

/** Daraja credentials for a shop: its own keys if set, otherwise the platform fallback. */
function resolveCreds(shop) {
  const hasOwn = shop.consumer_key && shop.consumer_secret && shop.shortcode && shop.passkey;
  if (hasOwn) {
    return {
      env: shop.daraja_env || 'sandbox',
      consumerKey: shop.consumer_key,
      consumerSecret: shop.consumer_secret,
      shortcode: shop.shortcode,
      passkey: shop.passkey,
      accountRef: shop.account_ref || shop.name || 'ShopPay',
      own: true,
    };
  }
  return {
    env: config.daraja.env,
    consumerKey: config.daraja.consumerKey,
    consumerSecret: config.daraja.consumerSecret,
    shortcode: config.daraja.shortcode,
    passkey: config.daraja.passkey,
    accountRef: config.daraja.accountRef,
    own: false,
  };
}

/**
 * From the shop's plain-English till/paybill details, derive the correct STK parameters.
 *  - 'paybill'     -> CustomerPayBillOnline, PartyB = paybill, AccountReference required
 *  - 'till'        -> CustomerBuyGoodsOnline, PartyB = till number (BusinessShortCode = till)
 *  - 'till_store'  -> CustomerBuyGoodsOnline, BusinessShortCode = HQ shortcode,
 *                     PartyB = STORE NUMBER (money settles to that store's till)
 * Operator name is NOT an STK parameter (Safaricom doesn't use it) — it's kept for
 * receipts/records and as an account-reference fallback.
 */
function chargeTarget(shop) {
  const type = shop.business_type || 'paybill';
  if (type === 'till_store') {
    return {
      transactionType: 'CustomerBuyGoodsOnline',
      partyB: shop.store_number || shop.shortcode,
      label: `Store ${shop.store_number || shop.shortcode} (HQ ${shop.shortcode})`,
    };
  }
  if (type === 'till') {
    return {
      transactionType: 'CustomerBuyGoodsOnline',
      partyB: shop.shortcode,
      label: `Buy Goods till ${shop.shortcode}`,
    };
  }
  return {
    transactionType: 'CustomerPayBillOnline',
    partyB: shop.shortcode,
    label: `Paybill ${shop.shortcode}`,
  };
}

function publicBase(req) {
  if (config.publicUrl) return config.publicUrl.replace(/\/$/, '');
  return `${req.protocol}://${req.get('host')}`;
}

function computeSale(lines, shopId) {
  const find = db.prepare('SELECT * FROM items WHERE id = ? AND shop_id = ? AND active = 1');
  const items = [];
  let currency = null;
  let subtotal = 0;
  let taxTotal = 0;
  for (const line of lines) {
    const qty = Math.max(1, Math.min(10000, parseInt(line.quantity, 10) || 1));
    const item = find.get(line.item_id, shopId);
    if (!item) {
      const e = new Error(`Item not found or inactive: id ${line.item_id}`);
      e.status = 404;
      throw e;
    }
    if (currency === null) currency = item.currency;
    if (item.currency !== currency) {
      const e = new Error('All items in one sale must share the same currency');
      e.status = 400;
      throw e;
    }
    const lineSub = item.unit_price * qty;
    const rate = Number(item.tax_rate || 0);
    const lineTax = Math.round(lineSub * rate * 100) / 100;
    subtotal = Math.round((subtotal + lineSub) * 100) / 100;
    taxTotal = Math.round((taxTotal + lineTax) * 100) / 100;
    items.push({
      item_id: item.id,
      name: item.name,
      unit_price: item.unit_price,
      quantity: qty,
      tax_rate: rate,
      line_total: Math.round((lineSub + lineTax) * 100) / 100,
    });
  }
  const first = items[0];
  const summary =
    items.length === 1
      ? `${first.name} × ${first.quantity}`
      : `${first.name} × ${first.quantity} + ${items.length - 1} more`;
  return {
    items,
    currency,
    subtotal,
    taxTotal,
    amount: Math.round((subtotal + taxTotal) * 100) / 100,
    summary,
    totalQty: items.reduce((a, b) => a + b.quantity, 0),
    first,
  };
}

function publicTxn(tx) {
  return {
    id: tx.id,
    status: tx.status,
    mpesa_receipt: tx.mpesa_receipt,
    result_code: tx.result_code,
    result_desc: tx.result_desc,
    item_name: tx.item_name,
    quantity: tx.quantity,
    subtotal: Math.round((tx.amount - tx.tax_amount) * 100) / 100,
    tax_amount: tx.tax_amount,
    amount: tx.amount,
    currency: tx.currency,
    items: JSON.parse(tx.items_json || '[]'),
    customer_phone: tx.customer_phone,
    created_at: tx.created_at,
  };
}

/**
 * POST /api/payments/prompt  (cashier or admin)
 * { items: [{item_id, quantity}, ...], customer_phone, account_reference? }
 * Legacy {item_id, quantity, customer_phone} also accepted.
 * Uses the CALLER'S SHOP credentials — money lands on that shop's own till/paybill.
 */
router.post('/prompt', authGuard, (req, res) => {
  let lines;
  if (Array.isArray(req.body.items)) lines = req.body.items;
  else if (req.body.item_id) lines = [{ item_id: req.body.item_id, quantity: req.body.quantity || 1 }];
  if (!lines || lines.length === 0) {
    return res.status(400).json({ error: 'Provide items: [{item_id, quantity}] (legacy: item_id + quantity)' });
  }
  if (lines.length > 50) return res.status(400).json({ error: 'Too many lines (max 50)' });

  let phone;
  try {
    phone = daraja.normalizePhone(req.body.customer_phone);
  } catch (e) {
    return res.status(e.status || 400).json({ error: e.message });
  }

  const shop = req.shop;
  let sale;
  try {
    sale = computeSale(lines, shop.id);
  } catch (e) {
    return res.status(e.status || 400).json({ error: e.message });
  }

  const creds = resolveCreds(shop);
  const useMock = shop.use_mock === 1 || (!creds.own && config.daraja.mock);
  const target = chargeTarget(shop);
  const callbackUrl = `${publicBase(req)}/api/payments/callback/${shop.shop_key}`;
  const accountRef = req.body.account_reference || creds.accountRef || shop.operator_name;
  const desc = `${sale.first.name}`.slice(0, 13) || 'Payment';

  const insertTxn = db.transaction(() => {
    db.prepare(INSERT_TXN).run(
      shop.id, sale.first.item_id, sale.summary, sale.first.unit_price, sale.totalQty,
      sale.amount, sale.taxTotal, JSON.stringify(sale.items), sale.currency, phone,
      accountRef, null, null, req.user.username
    );
    return db.prepare('SELECT * FROM transactions WHERE id = last_insert_rowid()').get();
  });

  if (useMock) {
    // ---- DEMO MODE (per shop): simulate the full STK flow ----
    const cr = 'ws_CO_MOCK_' + Date.now();
    const mr = 'MOCK-MR-' + Date.now();
    db.prepare('UPDATE transactions SET merchant_request_id=?, checkout_request_id=? WHERE id=?')
      .run(mr, cr, insertTxn().id);

    // Auto-complete after ~12 seconds, like a customer typing their PIN.
    setTimeout(() => {
      const receipt =
        'Q' + Math.random().toString(36).slice(2, 9).toUpperCase() +
        Math.floor(Math.random() * 90 + 10);
      markResult.run('SUCCESS', receipt, 0, 'Success. Mock payment accepted.', cr, shop.id);
      console.log(`[mock] shop ${shop.id}: auto-completed ${cr} receipt ${receipt}`);
    }, 12000);

    const tx = db.prepare('SELECT * FROM transactions WHERE checkout_request_id = ?').get(cr);
    return res.json({
      transaction_id: tx.id,
      checkout_request_id: cr,
      status: 'PENDING',
      items: sale.items,
      subtotal: sale.subtotal,
      tax_amount: sale.taxTotal,
      amount: sale.amount,
      currency: sale.currency,
      mode: 'MOCK',
      message: 'DEMO MODE (shop is in test mode): simulated STK push — auto-completes in ~12 seconds.',
    });
  }

  // ---- REAL Daraja call with THIS SHOP's keys ----
  daraja
    .stkPush(creds, {
      phone,
      amount: sale.amount,
      accountRef,
      desc,
      callbackUrl,
      transactionType: target.transactionType,
      partyB: target.partyB,
    })
    .then((r) => {
      const tx = insertTxn();
      db.prepare('UPDATE transactions SET merchant_request_id=?, checkout_request_id=? WHERE id=?')
        .run(r.MerchantRequestID, r.CheckoutRequestID, tx.id);
      res.json({
        transaction_id: tx.id,
        checkout_request_id: r.CheckoutRequestID,
        status: 'PENDING',
        items: sale.items,
        subtotal: sale.subtotal,
        tax_amount: sale.taxTotal,
        amount: sale.amount,
        currency: sale.currency,
        mode: (creds.env || 'sandbox').toUpperCase(),
        charge_target: target.label,
        operator: shop.operator_name || null,
        message: r.CustomerMessage || 'STK push sent. Ask the customer to enter their M-Pesa PIN.',
      });
    })
    .catch((e) => res.status(502).json({ error: e.message }));
});

/**
 * POST /api/payments/callback/:shopKey — PUBLIC, called by Safaricom.
 * The shopKey in the URL (unique per shop, set in the STK request) routes the
 * confirmation to the right tenant. Rotatable via POST /api/shops/me/regenerate-key.
 */
router.post('/callback/:shopKey', (req, res) => {
  console.log('[callback]', req.params.shopKey, JSON.stringify(req.body));
  const shop = db.prepare('SELECT * FROM shops WHERE shop_key = ?').get(req.params.shopKey);
  if (shop) {
    const cb = daraja.parseStkCallback(req.body);
    if (cb) {
      if (cb.resultCode === 0) {
        markResult.run('SUCCESS', cb.mpesaReceipt, cb.resultCode, cb.resultDesc, cb.checkoutRequestId, shop.id);
      } else {
        markResult.run('FAILED', null, cb.resultCode, cb.resultDesc, cb.checkoutRequestId, shop.id);
      }
    }
  } else {
    console.log('[callback] unknown shop key — ignoring payload');
  }
  res.json({ ResultCode: 0, ResultDesc: 'Accepted' });
});

/** GET /api/payments/status/:checkoutRequestId — polled by the app until not PENDING */
router.get('/status/:checkout', authGuard, (req, res) => {
  touchTimeout.run();
  const tx = db
    .prepare('SELECT * FROM transactions WHERE checkout_request_id = ? AND shop_id = ?')
    .get(req.params.checkout, req.user.shop_id);
  if (!tx) return res.status(404).json({ error: 'Transaction not found' });
  res.json(publicTxn(tx));
});

/** POST /api/payments/query/:checkoutRequestId — manual STK query fallback */
router.post('/query/:checkout', authGuard, (req, res) => {
  const tx = db
    .prepare('SELECT * FROM transactions WHERE checkout_request_id = ? AND shop_id = ?')
    .get(req.params.checkout, req.user.shop_id);
  if (!tx) return res.status(404).json({ error: 'Transaction not found' });
  const shop = req.shop;
  if (shop.use_mock === 1) return res.json({ ResultDesc: 'Shop in demo mode — nothing to query', status: tx.status });
  daraja
    .stkQuery(resolveCreds(shop), tx.checkout_request_id)
    .then((r) => {
      if (String(r.ResultCode) === '0') {
        markResult.run('SUCCESS', null, 0, r.ResultDesc, tx.checkout_request_id, shop.id);
      } else if (r.ResultCode !== undefined && r.errorMessage === undefined) {
        markResult.run('FAILED', null, Number(r.ResultCode) || null, r.ResultDesc || 'Failed', tx.checkout_request_id, shop.id);
      }
      res.json(r);
    })
    .catch((e) => res.status(502).json({ error: e.message }));
});

module.exports = router;
