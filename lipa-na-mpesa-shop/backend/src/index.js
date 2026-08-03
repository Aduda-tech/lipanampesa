'use strict';
const path = require('path');
const fs = require('fs');
const express = require('express');
const cors = require('cors');
const morgan = require('morgan');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const qrcode = require('qrcode');
const config = require('./config');
const discovery = require('./discovery');
require('./db'); // initialise schema + migrations + seed on boot

const authRoutes = require('./routes/auth.routes');
const shopRoutes = require('./routes/shops.routes');
const itemRoutes = require('./routes/items.routes');
const paymentRoutes = require('./routes/payments.routes');
const transactionRoutes = require('./routes/transactions.routes');

const VERSION = '2.0.0';

const app = express();
app.set('trust proxy', 1); // correct client IPs behind proxies/Render (for rate limits)
app.use(helmet());
app.use(cors());
app.use(express.json({ limit: '2mb' }));
app.use(morgan('dev'));

// Basic abuse protection
const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many login attempts — try again in a few minutes.' },
});
const promptLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many payment prompts — slow down a little.' },
});

app.get('/health', (req, res) => res.json({ ok: true, time: new Date().toISOString() }));

app.get('/', (req, res) =>
  res.json({
    name: 'Lipa na M-Pesa Shop Pay API — multi-shop SaaS',
    version: VERSION,
    register_shop: '/register',
    install_page: '/install',
    docs: 'See README.md and docs/ for setup, cloud deploy and the sandbox -> production switch.',
  })
);

// Public config for the Android app (no secrets)
app.get('/api/config', (req, res) =>
  res.json({
    version: VERSION,
    multi_shop: true,
    default_currency: config.defaultCurrency,
    currencies: config.currencies,
    register_url: '/register',
    discovery_service: `_${discovery.SERVICE_TYPE}._tcp.local`,
  })
);

/* ---------------- Static onboarding pages ---------------- */

const APK_PATH = path.join(__dirname, '..', 'public', 'app.apk');
const publicBase = (req) =>
  config.publicUrl ? config.publicUrl.replace(/\/$/, '') : `${req.protocol}://${req.get('host')}`;

const PAGE_CSS = `
  body{font-family:system-ui,Arial;margin:0;background:#f5f5f5;color:#222}
  .card{max-width:480px;margin:24px auto;background:#fff;border-radius:16px;padding:28px;box-shadow:0 2px 12px rgba(0,0,0,.08)}
  h1{color:#43B02A;font-size:24px;margin:0 0 4px}
  .step{margin:20px 0;padding:16px;border:1px solid #eee;border-radius:12px}
  .btn{display:block;text-align:center;background:#43B02A;color:#fff;text-decoration:none;padding:14px;border-radius:10px;font-weight:700;font-size:17px;border:0;cursor:pointer;width:100%;box-sizing:border-box}
  .btn.off{background:#bbb}
  .btn.alt{background:#2E7D1B}
  small{color:#777}
  code{background:#f0f0f0;padding:2px 6px;border-radius:6px}
  .qr{text-align:center;margin-top:8px}
  input{width:100%;box-sizing:border-box;padding:12px;margin:6px 0;border:1px solid #ccc;border-radius:8px;font-size:16px}
  #result{margin-top:14px;padding:12px;border-radius:8px;display:none;white-space:pre-wrap;font-size:14px}
`;

/** GET /app.apk — the Android app, served by the backend itself. */
app.get('/app.apk', (req, res) => {
  if (fs.existsSync(APK_PATH)) {
    res.download(APK_PATH, 'LipaNaMpesaShop.apk');
  } else {
    res
      .status(404)
      .send(
        'The app is not on this server yet. Place the APK at backend/public/app.apk ' +
        '(see docs/BUILD_APK.md), or install it from the GitHub Actions artifact.'
      );
  }
});

/** GET /install — download APK + tap-to-connect link (deep link into the app) + QR. */
app.get('/install', async (req, res) => {
  const base = publicBase(req);
  const link = discovery.connectLink(base);
  let qrImg = '';
  try {
    const dataUrl = await qrcode.toDataURL(link, { margin: 1, width: 260 });
    qrImg = `<img src="${dataUrl}" alt="setup QR" style="image-rendering:pixelated"/>`;
  } catch { /* optional */ }
  const hasApk = fs.existsSync(APK_PATH);
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.send(`<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Lipa na M-Pesa Shop — Install</title><style>${PAGE_CSS}</style></head><body><div class="card">
  <h1>🟢 Lipa na M-Pesa Shop</h1>
  <small>Server v${VERSION}</small>
  <div class="step">
    <b>1. Install the app</b>
    <p>Tap below on this phone. Allow “install unknown apps” when Android asks.</p>
    <a class="btn ${hasApk ? '' : 'off'}" href="/app.apk">⬇ Download app ${hasApk ? '' : '(not uploaded yet)'}</a>
  </div>
  <div class="step">
    <b>2. Connect the app to this server</b>
    <p>After installing, tap this button — it opens the app and sets the server for you
    (nothing to type):</p>
    <a class="btn alt" href="${link}">🔗 Open app &amp; connect</a>
    <div class="qr">${qrImg}</div>
    <p style="text-align:center"><small>or scan the QR from the login screen · manually: <code>${base}/</code></small></p>
  </div>
  <div class="step">
    <b>3. Log in</b>
    <p>Your shop admin gives you a username + password. Shop owner? Create one at
    <a href="/register">/register</a>.</p>
  </div>
  <small>Works anywhere: over shop Wi-Fi or mobile data — the server lives in the cloud.</small>
</div></body></html>`);
});

/** GET /register — self-serve shop onboarding (creates shop + admin account). */
app.get('/register', (req, res) => {
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  res.send(`<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Register your shop — Lipa na M-Pesa Shop</title><style>${PAGE_CSS}</style></head><body><div class="card">
  <h1>🟢 Open your shop</h1>
  <small>Free to start — you begin in DEMO mode (simulated payments) and enter your Daraja
  keys later to receive real M-Pesa.</small>
  <div class="step">
    <b>Create shop + admin account</b>
    <form id="f">
      <input name="shop_name" placeholder="Shop name (e.g. Westlands Cyber & Accessories)" required>
      <input name="admin_username" placeholder="Admin username (e.g. westlands_admin)" required>
      <input name="admin_password" type="password" placeholder="Admin password (6+ chars)" required>
      <input name="display_name" placeholder="Your name (optional)">
      <button class="btn" type="submit">Create my shop</button>
    </form>
    <div id="result"></div>
  </div>
  <small>Then on each cashier phone: open <a href="/install">/install</a>, install the app,
  tap “Open app &amp; connect”, and log in. Admin adds cashiers inside the app.</small>
</div>
<script>
document.getElementById('f').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target, r = document.getElementById('result');
  const body = Object.fromEntries(new FormData(f).entries());
  r.style.display = 'block'; r.style.background = '#eee'; r.textContent = 'Creating…';
  try {
    const resp = await fetch('/api/shops/register', {
      method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(body)
    });
    const data = await resp.json();
    if (resp.ok) {
      r.style.background = '#E8F5E9';
      r.textContent = '✔ Shop "' + data.shop_name + '" created!\\n\\n' +
        'Log in on the app with:\\n  username: ' + data.admin_username + '\\n  password: (what you chose)\\n\\n' +
        'Your unique M-Pesa callback URL (paste in Daraja portal when going live):\\n' + data.callback_url +
        '\\n\\nNext: /install on every cashier phone, then add cashiers in the app (Admin → Add user).';
    } else {
      r.style.background = '#FFEBEE'; r.textContent = '✖ ' + (data.error || 'Failed');
    }
  } catch (err) { r.style.background = '#FFEBEE'; r.textContent = '✖ Network error'; }
});
</script></body></html>`);
});

app.use('/api/auth/login', loginLimiter);
app.use('/api/payments/prompt', promptLimiter);
app.use('/api/auth', authRoutes);
app.use('/api/shops', shopRoutes);
app.use('/api/items', itemRoutes);
app.use('/api/payments', paymentRoutes);
app.use('/api/transactions', transactionRoutes);

app.use((req, res) => res.status(404).json({ error: 'Not found' }));
// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error(err);
  res.status(err.status || 500).json({ error: err.message || 'Server error' });
});

app.listen(config.port, '0.0.0.0', () => {
  console.log(`
============================================================
  Lipa na M-Pesa Shop Pay API  v${VERSION}  (multi-shop SaaS)
  http://localhost:${config.port}

  Public URL : ${config.publicUrl || '(not set — using request Host; set PUBLIC_URL in cloud)'}
  Fallback Daraja: ${config.daraja.env} · mock=${config.daraja.mock}
  Register   : /register   Install: /install   Health: /health
  DB         : ${config.dbFile}
============================================================
`);
  // Local-network auto-detection (optional convenience; harmless in the cloud)
  discovery.advertise(config.port, VERSION);
  if (!config.publicUrl) discovery.printSetupInfo(config.port);
});
