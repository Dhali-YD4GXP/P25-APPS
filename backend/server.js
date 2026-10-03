'use strict';

/*
 * P25 APX1000 PoC backend
 * -----------------------
 *  - REST API: auth (sign up / sign in), talkgroup channel management, health
 *  - WebSocket (/ws): signaling, floor control and Codec 2 frame relay
 *
 * Audio frames are relayed opaquely as binary (the Android app already
 * encodes/decodes with Codec 2), so the server never decodes voice.
 *
 * The container listens on PORT (default 9500); docker-compose maps host
 * port 95 -> container 9500. Public exposure is done by Cloudflare Tunnel
 * pointing at http://localhost:95.
 */

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const express = require('express');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '9500', 10);
const HOST = process.env.HOST || '0.0.0.0';

// ---------------------------------------------------------------------------
// State (in-memory PoC store)
// ---------------------------------------------------------------------------
const accounts = new Map(); // username(lower) -> { username, unitId, salt, hash }
const unitIds = new Map();  // unitId(upper)    -> username(lower)
const channels = new Map(); // code(upper)      -> { name, code, zone, alias, secure, allowedIds }
const activity = [];        // completed TX: { unitId, username, channel, start, end, durationMs }
const activeTx = new Map(); // channel -> { unitId, username, start }

channels.set('P25-CH-1000', { name: 'P25', code: 'P25-CH-1000', zone: 'ZONE 1', alias: null, secure: false, allowedIds: new Set() });

function hashPassword(password, salt) {
  return crypto.createHash('sha256').update(salt + ':' + password).digest('hex');
}

// ---------------------------------------------------------------------------
// REST API
// ---------------------------------------------------------------------------
const app = express();
app.set('trust proxy', true);
app.use(express.json());
app.use((req, res, next) => {
  const fwd = req.headers['x-forwarded-for'] || req.socket.remoteAddress || '';
  const isLocalHealth = req.url === '/api/health' &&
    /^(::1|127\.0\.0\.1|::ffff:127\.0\.0\.1)$/.test(req.socket.remoteAddress || '');
  if (!isLocalHealth) console.log('[http]', req.method, req.url, '-', fwd);
  next();
});
app.use((req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Headers', 'Content-Type');
  res.set('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

app.get('/', (req, res) => {
  res.json({ service: 'p25-apx1000-backend', version: '1.0.0', endpoints: ['/api/health', '/api/auth/signup', '/api/auth/login', '/api/channels', '/ws', '/p25.apk', '/apk', '/download'] });
});

app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    time: new Date().toISOString(),
    channels: channels.size,
    online: [...wss.clients].filter((c) => c.meta && c.meta.unitId).length,
  });
});

// Download the latest debug APK (host build dir mounted read-only at /srv/apk).
// Aliases so people don't have to remember the exact name.
app.get(['/p25.apk', '/apk', '/download'], (req, res) => {
  res.download('/srv/apk/app-debug.apk', 'p25-apx1000.apk', (err) => {
    if (err && !res.headersSent) {
      res.status(404).json({ error: 'APK not available yet' });
    }
  });
});

app.post('/api/auth/signup', (req, res) => {
  const { username, password, unitId } = req.body || {};
  if (!username || !password || !unitId) {
    return res.status(400).json({ error: 'username, password and unitId are required' });
  }
  const u = String(username).trim().toLowerCase();
  const id = String(unitId).trim().toUpperCase();
  if (accounts.has(u)) return res.status(409).json({ error: 'Username already registered' });
  if (unitIds.has(id)) return res.status(409).json({ error: `Unit ID ${id} is already in use` });
  const salt = crypto.randomBytes(8).toString('hex');
  accounts.set(u, { username: String(username).trim(), unitId: id, salt, hash: hashPassword(password, salt) });
  unitIds.set(id, u);
  res.status(201).json({ username, unitId: id });
});

app.post('/api/auth/login', (req, res) => {
  const { username, password, unitId } = req.body || {};
  const u = String(username || '').trim().toLowerCase();
  const account = accounts.get(u);
  if (!account) return res.status(401).json({ error: 'Unknown user' });
  if (unitId && account.unitId !== String(unitId).trim().toUpperCase()) {
    return res.status(401).json({ error: 'Unit ID does not match this account' });
  }
  if (account.hash !== hashPassword(password || '', account.salt)) {
    return res.status(401).json({ error: 'Incorrect password' });
  }
  res.json({ username: account.username, unitId: account.unitId });
});

function channelJson(c) {
  return {
    name: c.name, code: c.code, zone: c.zone,
    alias: c.alias || null, secure: !!c.secure,
    allowedIds: [...(c.allowedIds || [])],
  };
}

app.get('/api/channels', (req, res) => {
  res.json({ channels: [...channels.values()].map(channelJson) });
});

// Add a channel. Generates a unique code when a name is given; admin may set
// an alias (shown on the radio UI) and mark it secure with an ID allowlist.
app.post('/api/channels', (req, res) => {
  const raw = String((req.body && req.body.nameOrCode) || '').trim();
  if (!raw) return res.status(400).json({ error: 'nameOrCode is required' });
  const isCode = /^P25-CH-\d{3,6}$/i.test(raw);
  let code = isCode ? raw.toUpperCase() : 'P25-CH-' + crypto.randomInt(1000, 9999);
  while (channels.has(code)) code = 'P25-CH-' + crypto.randomInt(1000, 9999);
  const ch = {
    name: isCode ? code : raw,
    code,
    zone: 'ZONE 1',
    alias: (req.body && req.body.alias) ? String(req.body.alias).trim() : null,
    secure: !!(req.body && req.body.secure),
    allowedIds: new Set(Array.isArray(req.body && req.body.allowedIds)
      ? req.body.allowedIds.map((s) => String(s).trim().toUpperCase()).filter(Boolean)
      : []),
  };
  channels.set(code, ch);
  if (!floors.has(code)) floors.set(code, { holder: null });
  console.log('[ch] added', code, 'alias=', ch.alias, 'secure=', ch.secure);
  res.status(201).json(channelJson(ch));
});

app.post('/api/channels/:code', (req, res) => {
  const code = String(req.params.code).toUpperCase();
  const ch = channels.get(code);
  if (!ch) return res.status(404).json({ error: 'channel not found' });
  if (req.body && req.body.alias !== undefined) ch.alias = req.body.alias ? String(req.body.alias).trim() : null;
  if (req.body && req.body.secure !== undefined) ch.secure = !!req.body.secure;
  if (req.body && req.body.allowedIds !== undefined) {
    ch.allowedIds = new Set(Array.isArray(req.body.allowedIds)
      ? req.body.allowedIds.map((s) => String(s).trim().toUpperCase()).filter(Boolean) : []);
  }
  res.json(channelJson(ch));
});

app.delete('/api/channels/:code', (req, res) => {
  const code = String(req.params.code).toUpperCase();
  if (!channels.delete(code)) return res.status(404).json({ error: 'channel not found' });
  floors.delete(code);
  console.log('[ch] deleted', code);
  res.json({ ok: true });
});

// OTA version manifest (written by the Gradle build into /srv/apk/version.json).
app.get('/api/version', (req, res) => {
  try {
    const v = JSON.parse(fs.readFileSync('/srv/apk/version.json', 'utf8'));
    v.apkUrl = v.apkUrl || '/p25.apk';
    res.json(v);
  } catch {
    res.json({ versionCode: 0, versionName: '0', apkUrl: '/p25.apk' });
  }
});

// Channel TX activity for the admin terminal.
app.get('/api/activity', (req, res) => {
  res.json({
    active: [...activeTx.values()],
    history: activity.slice(0, 100),
  });
});

app.get('/admin', (req, res) => {
  res.sendFile(require('path').join(__dirname, 'admin.html'));
});

// ---------------------------------------------------------------------------
// WebSocket signaling + floor control + frame relay
// ---------------------------------------------------------------------------
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname !== '/ws') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
});

// floor holders keyed by channel code
const floors = new Map(); // code -> { holder: unitId|null }

function membersOf(code) {
  return [...wss.clients].filter((c) => c.meta && c.meta.channel === code);
}

function broadcast(code, obj, except) {
  const payload = JSON.stringify(obj);
  for (const c of membersOf(code)) {
    if (c !== except && c.readyState === c.OPEN) c.send(payload);
  }
}

wss.on('connection', (ws, req) => {
  ws.meta = { unitId: null, channel: null };
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  console.log('[ws] connect', req && req.socket ? req.socket.remoteAddress : '');

  ws.on('message', (data, isBinary) => {
    // ---- binary: relayed Codec 2 voice frame ----
    if (isBinary) {
      const { unitId, channel } = ws.meta;
      if (!unitId || !channel) return;
      const floor = floors.get(channel);
      if (!floor || floor.holder !== unitId) {
        if (!ws._dropLogged) { console.log('[ws] frame dropped (no floor):', unitId); ws._dropLogged = true; }
        return; // only the floor holder may talk
      }
      ws._frames = (ws._frames || 0) + 1;
      const size = Buffer.isBuffer(data) ? data.length : (data.byteLength || 0);
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
      let relayed = 0;
      for (const c of membersOf(channel)) {
        if (c !== ws && c.readyState === c.OPEN) { c.send(buf, { binary: true }); relayed++; }
      }
      if (ws._frames % 50 === 1) console.log('[ws] frames', unitId, channel, 'count=' + ws._frames, 'size=' + size, 'relayed=' + relayed);
      return;
    }

    // ---- text: control message ----
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      return send(ws, { type: 'error', message: 'invalid JSON' });
    }

    switch (msg.type) {
      case 'hello':
        return onHello(ws, msg);
      case 'join':
        return onJoin(ws, msg);
      case 'ptt':
        return onPtt(ws, msg);
      case 'ping':
        return send(ws, { type: 'pong', t: msg.t || Date.now() });
      default:
        return send(ws, { type: 'error', message: `unknown type: ${msg.type}` });
    }
  });

  ws.on('close', () => {
    const { unitId, channel } = ws.meta;
    if (!unitId || !channel) return;
    console.log('[ws] close', unitId, channel);
    const floor = floors.get(channel);
    if (floor && floor.holder === unitId) {
      floor.holder = null;
      recordTx(channel);
      broadcast(channel, { type: 'floor', busy: false, holder: null, reason: 'released' });
    }
    broadcast(channel, { type: 'member', action: 'leave', unitId }, ws);
  });
});

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

function onHello(ws, msg) {
  const unitId = String(msg.unitId || '').trim().toUpperCase();
  if (!unitId) return send(ws, { type: 'error', message: 'unitId required' });
  ws.meta.unitId = unitId;
  ws.meta.username = msg.username ? String(msg.username).trim() : null;
  console.log('[ws] hello', unitId, ws.meta.username || '');
  send(ws, { type: 'welcome', unitId, server: 'p25-apx1000' });
  if (msg.channel) onJoin(ws, { channel: msg.channel });
}

function usernameFor(ws) {
  return ws.meta.username || unitIds.get(ws.meta.unitId) || ws.meta.unitId;
}

function recordTx(channel) {
  const t = activeTx.get(channel);
  if (!t) return;
  activeTx.delete(channel);
  activity.unshift({ unitId: t.unitId, username: t.username, channel, start: t.start, end: Date.now(), durationMs: Date.now() - t.start });
  if (activity.length > 200) activity.length = 200;
}

function onJoin(ws, msg) {
  const code = String(msg.channel || '').trim().toUpperCase();
  if (!ws.meta.unitId) return send(ws, { type: 'error', message: 'send hello first' });
  if (!code) return send(ws, { type: 'error', message: 'channel required' });

  // Secured channel: only allow-listed IDs may join.
  const existing = channels.get(code);
  if (existing && existing.secure && !(existing.allowedIds || new Set()).has(ws.meta.unitId)) {
    console.log('[ws] join denied', ws.meta.unitId, code, '(secure)');
    return send(ws, { type: 'error', message: 'channel secured, ID not authorized' });
  }

  // leave previous channel
  if (ws.meta.channel && ws.meta.channel !== code) {
    broadcast(ws.meta.channel, { type: 'member', action: 'leave', unitId: ws.meta.unitId }, ws);
  }
  ws.meta.channel = code;
  if (!floors.has(code)) floors.set(code, { holder: null });
  if (!channels.has(code)) channels.set(code, { name: code, code, zone: 'ZONE 1', alias: null, secure: false, allowedIds: new Set() });
  console.log('[ws] join', ws.meta.unitId, code);

  send(ws, {
    type: 'joined',
    channel: channelJson(channels.get(code)),
    members: membersOf(code).map((c) => c.meta.unitId),
    floor: floors.get(code).holder,
  });
  broadcast(code, { type: 'member', action: 'join', unitId: ws.meta.unitId }, ws);
}

function onPtt(ws, msg) {
  const { unitId, channel } = ws.meta;
  if (!unitId || !channel) return send(ws, { type: 'error', message: 'join a channel first' });
  const floor = floors.get(channel) || { holder: null };
  floors.set(channel, floor);
  const state = msg.state === 'up' ? 'up' : 'down';

  if (state === 'down') {
    if (floor.holder && floor.holder !== unitId) {
      console.log('[ws] ptt denied', unitId, channel, 'held by', floor.holder);
      return send(ws, { type: 'floor', busy: true, holder: floor.holder, reason: 'occupied', granted: false });
    }
    floor.holder = unitId;
    activeTx.set(channel, { unitId, username: usernameFor(ws), start: Date.now() });
    console.log('[ws] ptt granted', unitId, channel);
    broadcast(channel, { type: 'floor', busy: true, holder: unitId, reason: 'granted', granted: true });
    broadcast(channel, { type: 'speaker', unitId }, ws);
    return;
  }

  // state === 'up'
  if (floor.holder === unitId) {
    floor.holder = null;
    recordTx(channel);
    console.log('[ws] ptt released', unitId, channel);
    broadcast(channel, { type: 'floor', busy: false, holder: null, reason: 'released' });
  }
}

// heartbeat: drop dead sockets
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);
wss.on('close', () => clearInterval(heartbeat));

server.listen(PORT, HOST, () => {
  console.log(`[p25-backend] listening on http://${HOST}:${PORT}  (ws: /ws)`);
});

module.exports = { app, server };
