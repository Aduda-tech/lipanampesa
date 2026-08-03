'use strict';
/**
 * Thin client for Safaricom's Daraja API (M-Pesa).
 * Sandbox    base URL: https://sandbox.safaricom.co.ke
 * Production base URL: https://api.safaricom.co.ke
 * Docs: https://developer.safaricom.co.ke
 *
 * v2.0: credentials are PER SHOP (each tenant charges to its own till/paybill).
 * Call sites build a `creds` object — see resolveCreds() in routes/payments.routes.js:
 * { env, consumerKey, consumerSecret, shortcode, passkey, accountRef }
 */
const config = require('./config');

const BASE_URLS = {
  sandbox: 'https://sandbox.safaricom.co.ke',
  production: 'https://api.safaricom.co.ke',
};

function baseUrl(creds) {
  return BASE_URLS[(creds.env || 'sandbox').toLowerCase()] || BASE_URLS.sandbox;
}

function timestamp() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  return (
    d.getFullYear().toString() +
    p(d.getMonth() + 1) +
    p(d.getDate()) +
    p(d.getHours()) +
    p(d.getMinutes()) +
    p(d.getSeconds())
  );
}

/** base64(shortcode + passkey + timestamp) as required by Lipa Na M-Pesa Online. */
function stkPassword(creds, ts) {
  return Buffer.from(creds.shortcode + creds.passkey + ts).toString('base64');
}

async function getAccessToken(creds) {
  const auth = Buffer.from(`${creds.consumerKey}:${creds.consumerSecret}`).toString('base64');
  const res = await fetch(`${baseUrl(creds)}/oauth/v1/generate?grant_type=client_credentials`, {
    headers: { Authorization: `Basic ${auth}` },
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) {
    throw new Error(`Daraja OAuth failed (${res.status}): ${JSON.stringify(data)}`);
  }
  return data.access_token;
}

function normalizePhone(input) {
  let p = String(input || '').replace(/[^\d+]/g, '');
  if (p.startsWith('+')) p = p.slice(1);
  if (p.startsWith('0')) p = '254' + p.slice(1);
  if (p.length === 9 && (p.startsWith('7') || p.startsWith('1'))) p = '254' + p;
  if (!/^254[71]\d{8}$/.test(p)) {
    const err = new Error(
      `Invalid Safaricom phone number "${input}". Use 07XXXXXXXX, 01XXXXXXXX or 2547XXXXXXXX.`
    );
    err.status = 400;
    throw err;
  }
  return p;
}

/** Lipa Na M-Pesa Online (STK Push). Returns Daraja response incl. CheckoutRequestID.
 *  opts.transactionType: 'CustomerPayBillOnline' (Paybill) | 'CustomerBuyGoodsOnline' (Till)
 *  opts.partyB: shortcode that RECEIVES the money — for store-based till networks this is
 *  the STORE NUMBER while BusinessShortCode stays the head-office shortcode. */
async function stkPush(creds, { phone, amount, accountRef, desc, callbackUrl, transactionType, partyB }) {
  const token = await getAccessToken(creds);
  const ts = timestamp();
  const body = {
    BusinessShortCode: creds.shortcode,
    Password: stkPassword(creds, ts),
    Timestamp: ts,
    TransactionType: transactionType || 'CustomerPayBillOnline',
    Amount: Math.max(1, Math.round(Number(amount))),
    PartyA: phone,
    PartyB: partyB || creds.shortcode,
    PhoneNumber: phone,
    CallBackURL: callbackUrl,
    AccountReference: (accountRef || creds.accountRef || 'ShopPay').slice(0, 12),
    TransactionDesc: (desc || 'Payment').slice(0, 13),
  };
  const res = await fetch(`${baseUrl(creds)}/mpesa/stkpush/v1/processrequest`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || data.ResponseCode !== '0') {
    throw new Error(`STK Push rejected (${res.status}): ${JSON.stringify(data)}`);
  }
  return data;
}

/** Manually query the status of an STK push (useful if a callback never arrives). */
async function stkQuery(creds, checkoutRequestId) {
  const token = await getAccessToken(creds);
  const ts = timestamp();
  const body = {
    BusinessShortCode: creds.shortcode,
    Password: stkPassword(creds, ts),
    Timestamp: ts,
    CheckoutRequestID: checkoutRequestId,
  };
  const res = await fetch(`${baseUrl(creds)}/mpesa/stkpushquery/v1/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  });
  return res.json().catch(() => ({}));
}

/** Parse the payload Safaricom sends to our callback URL. */
function parseStkCallback(payload) {
  const cb = payload && payload.Body && payload.Body.stkCallback;
  if (!cb) return null;
  const meta = {};
  const items = (cb.CallbackMetadata && cb.CallbackMetadata.Item) || [];
  for (const item of items) meta[item.Name] = item.Value;
  return {
    merchantRequestId: cb.MerchantRequestID,
    checkoutRequestId: cb.CheckoutRequestID,
    resultCode: Number(cb.ResultCode),
    resultDesc: cb.ResultDesc || '',
    amount: meta.Amount,
    mpesaReceipt: meta.MpesaReceiptNumber || null,
    transactionDate: meta.TransactionDate,
    phone: meta.PhoneNumber ? String(meta.PhoneNumber) : null,
  };
}

module.exports = { getAccessToken, normalizePhone, stkPush, stkQuery, parseStkCallback };
