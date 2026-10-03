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
const express = require('express');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '9500', 10);
const HOST = process.env.HOST || '0.0.0.0';

// ---------------------------------------------------------------------------
// State (in-memory PoC store)
// ---------------------------------------------------------------------------
const accounts = new Map(); // username(lower) -> { username, unitId, salt, hash }
const unitIds = new Map();  // unitId(upper)    -> username(lower)
const channels = new Map(); // code(upper)      -> { name, code, zone }

channels.set('P25-CH-1000', { name: 'APX-1000', code: 'P25-CH-1000', zone: 'ZONE 1' });

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

app.get('/api/channels', (req, res) => {
  res.json({ channels: [...channels.values()] });
});

app.post('/api/channels', (req, res) => {
  const raw = String((req.body && req.body.nameOrCode) || '').trim();
  if (!raw) return res.status(400).json({ error: 'nameOrCode is required' });
  const isCode = /^P25-CH-\d{3,6}$/i.test(raw);
  const code = isCode ? raw.toUpperCase() : 'TG-' + crypto.createHash('sha1').update(raw.toLowerCase()).digest('hex').slice(0, 8);
  if (!channels.has(code)) {
    channels.set(code, { name: isCode ? code : raw, code, zone: 'ZONE 1' });
  }
  res.status(201).json(channels.get(code));
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
      if (!floor || floor.holder !== unitId) return; // only the floor holder may talk
      const buf = Buffer.isBuffer(data) ? data : Buffer.from(data);
      for (const c of membersOf(channel)) {
        if (c !== ws && c.readyState === c.OPEN) c.send(buf, { binary: true });
      }
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
  console.log('[ws] hello', unitId);
  send(ws, { type: 'welcome', unitId, server: 'p25-apx1000' });
  if (msg.channel) onJoin(ws, { channel: msg.channel });
}

function onJoin(ws, msg) {
  const code = String(msg.channel || '').trim().toUpperCase();
  if (!ws.meta.unitId) return send(ws, { type: 'error', message: 'send hello first' });
  if (!code) return send(ws, { type: 'error', message: 'channel required' });

  // leave previous channel
  if (ws.meta.channel && ws.meta.channel !== code) {
    broadcast(ws.meta.channel, { type: 'member', action: 'leave', unitId: ws.meta.unitId }, ws);
  }
  ws.meta.channel = code;
  if (!floors.has(code)) floors.set(code, { holder: null });
  if (!channels.has(code)) channels.set(code, { name: code, code, zone: 'ZONE 1' });
  console.log('[ws] join', ws.meta.unitId, code);

  send(ws, {
    type: 'joined',
    channel: channels.get(code),
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
    console.log('[ws] ptt granted', unitId, channel);
    broadcast(channel, { type: 'floor', busy: true, holder: unitId, reason: 'granted', granted: true });
    broadcast(channel, { type: 'speaker', unitId }, ws);
    return;
  }

  // state === 'up'
  if (floor.holder === unitId) {
    floor.holder = null;
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
