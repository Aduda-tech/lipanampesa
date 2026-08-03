'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });

const env = process.env;

module.exports = {
  port: parseInt(env.PORT || '3000', 10),
  jwtSecret: env.JWT_SECRET || 'dev-secret-change-me',
  dbFile: path.resolve(__dirname, '..', env.DB_FILE || 'data/shop.db'),
  defaultCurrency: (env.DEFAULT_CURRENCY || 'KES').toUpperCase(),
  currencies: (env.SUPPORTED_CURRENCIES || 'KES,USD,EUR,GBP,TZS,UGX,RWF')
    .split(',')
    .map((c) => c.trim().toUpperCase())
    .filter(Boolean),
  /** Public base URL of THIS server (used to build per-shop Daraja callback URLs).
   *  Render sets RENDER_EXTERNAL_URL automatically; override with PUBLIC_URL if you use
   *  a custom domain, e.g. https://pay.mycompany.com */
  publicUrl: env.PUBLIC_URL || env.RENDER_EXTERNAL_URL || '',
  daraja: {
    // Global FALLBACK credentials (used when a shop hasn't set its own keys in
    // Settings — e.g. your own sandbox keys so every new shop can test instantly).
    env: (env.DARAJA_ENV || 'sandbox').toLowerCase(),
    mock: (env.DARAJA_MOCK || 'true').toLowerCase() === 'true',
    consumerKey: env.DARAJA_CONSUMER_KEY || '',
    consumerSecret: env.DARAJA_CONSUMER_SECRET || '',
    shortcode: env.DARAJA_SHORTCODE || '174379',
    passkey:
      env.DARAJA_PASSKEY ||
      'bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919',
    accountRef: env.DARAJA_ACCOUNT_REF || 'ShopPay',
  },
};
