'use strict';
/**
 * Zero-setup networking helpers:
 *  - Advertises the backend on the LAN via mDNS/Bonjour as "_mpesa-shop._tcp.local."
 *    so the Android app finds the server automatically (no IP typing).
 *  - Prints the shop's LAN URLs + a setup QR code to the console at startup.
 *    Cashiers just scan the QR (or let the app auto-detect) — nothing to type.
 */
const os = require('os');
const qrcode = require('qrcode');

const SERVICE_TYPE = 'mpesa-shop'; // becomes _mpesa-shop._tcp.local.

/** All LAN URLs the server is reachable on (e.g. http://192.168.1.50:3000). */
function lanUrls(port) {
  const urls = [];
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const net of nets[name] || []) {
      if (net.family === 'IPv4' && !net.internal) urls.push(`http://${net.address}:${port}`);
    }
  }
  if (urls.length === 0) urls.push(`http://localhost:${port}`);
  return urls;
}

/** The QR/link payload the app understands. */
function connectLink(url) {
  return `mpesashop://connect?url=${encodeURIComponent(url + '/')}`;
}

/** Start mDNS advertisement. Safe no-op if it fails (app discovery just falls back to QR/manual). */
function advertise(port, version) {
  try {
    const ciao = require('@homebridge/ciao');
    const responder = ciao.getResponder();
    const service = responder.createService({
      name: 'Lipa Na Mpesa Shop Backend',
      type: SERVICE_TYPE,
      port,
      txt: { version: version || '1.2.0' },
    });
    service
      .advertise()
      .then(() => console.log(`[discovery] Advertising _${SERVICE_TYPE}._tcp.local on the LAN (app auto-detect ON)`))
      .catch((e) => console.log('[discovery] mDNS advertise failed (QR/manual still works):', e.message));
    const stop = () => {
      service.end().catch(() => {}).finally(() => process.exit(0));
    };
    process.on('SIGINT', stop);
    process.on('SIGTERM', stop);
  } catch (e) {
    console.log('[discovery] mDNS unavailable (QR/manual still works):', e.message);
  }
}

/** Print connection URLs + setup QR to the terminal at startup. */
async function printSetupInfo(port) {
  const urls = lanUrls(port);
  const lines = [];
  lines.push('');
  lines.push('  ------------------------------------------------------------');
  lines.push('  📱 CASHIER PHONE SETUP (same Wi-Fi/hotspot — no bundles needed)');
  lines.push('  ------------------------------------------------------------');
  lines.push('  The app usually finds this server BY ITSELF (auto-detect).');
  lines.push('  If it does not, either:');
  lines.push('    a) open this page on the phone and install the app + scan the code:');
  urls.forEach((u) => lines.push(`       → ${u}/install`));
  lines.push('    b) or scan this setup QR from the login screen ("SCAN SETUP QR"):');
  console.log(lines.join('\n'));
  try {
    const qr = await qrcode.toString(connectLink(urls[0]), { type: 'terminal', small: true });
    console.log(qr);
  } catch {
    console.log(`  (QR unavailable — connect link: ${connectLink(urls[0])})`);
  }
  console.log(`  Server URL for manual entry: ${urls[0]}/\n`);
}

module.exports = { advertise, printSetupInfo, lanUrls, connectLink, SERVICE_TYPE };
