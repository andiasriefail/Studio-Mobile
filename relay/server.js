'use strict';
/**
 * Studio Mobile MCP relay. One file, Node 18+, no dependencies.
 *
 *   AI (Claude / ChatGPT / Gemini CLI) --POST /s/<secret>/mcp-->  relay  <--long-poll-- phone
 *
 * The phone has no public address, so it calls out to GET /s/<secret>/phone/poll and answers each request with
 * POST /s/<secret>/phone/reply/<id>. The secret in the path is the user's private key: whoever knows it can reach
 * the phone, so make it long and random (e.g. `openssl rand -hex 24`) and serve the relay over HTTPS.
 *
 * Environment:
 *   PORT                 default 8787
 *   RELAY_SECRETS        optional comma separated allow-list of secrets. Empty = any secret of 16+ safe chars.
 *   AI_TIMEOUT_SECONDS   how long an AI request may wait for the phone, default 120
 *   AI_TOKEN             optional. When set, every AI request to /mcp must send `Authorization: Bearer <token>`
 *                        (or `X-API-Key: <token>`). Use it with clients that can send headers, such as Claude Code
 *                        and Gemini CLI. Leave it unset for clients that cannot (e.g. the ChatGPT connector form).
 *   PHONE_TOKEN          optional. When set, the phone must send the same value as its access token.
 */
const http = require('http');
const crypto = require('crypto');

const PORT = Number(process.env.PORT || 8787);
const ALLOWED = (process.env.RELAY_SECRETS || '').split(',').map((s) => s.trim()).filter(Boolean);
const AI_TIMEOUT_MS = Number(process.env.AI_TIMEOUT_SECONDS || 120) * 1000;
const AI_TOKEN = (process.env.AI_TOKEN || '').trim();
const PHONE_TOKEN = (process.env.PHONE_TOKEN || '').trim();
const POLL_MAX_MS = 30000;
const PHONE_ONLINE_MS = 45000;
const MAX_BODY = 8 * 1024 * 1024;
const MAX_QUEUE = 50;

const sessions = new Map(); // secret -> { queue, waiting, pending, lastSeen }

function getSession(secret) {
  let s = sessions.get(secret);
  if (!s) { s = { queue: [], waiting: null, pending: new Map(), lastSeen: 0 }; sessions.set(secret, s); }
  return s;
}

function secretAllowed(secret) {
  if (ALLOWED.length) {
    const hash = (v) => crypto.createHash('sha256').update(v).digest();
    return ALLOWED.some((a) => crypto.timingSafeEqual(hash(a), hash(secret)));
  }
  return /^[A-Za-z0-9_-]{16,128}$/.test(secret);
}

/** Accepts `Authorization: Bearer <token>` or `X-API-Key: <token>`. No expected token means no check. */
function tokenOk(req, expected) {
  if (!expected) return true;
  let given = '';
  const auth = String(req.headers['authorization'] || '');
  const bearer = auth.match(/^Bearer\s+(.+)$/i);
  if (bearer) given = bearer[1].trim();
  else if (req.headers['x-api-key']) given = String(req.headers['x-api-key']).trim();
  const hash = (v) => crypto.createHash('sha256').update(v).digest();
  return crypto.timingSafeEqual(hash(given), hash(expected));
}

function unauthorized(res, what) {
  res.setHeader('WWW-Authenticate', 'Bearer realm="studio-mobile-relay"');
  return send(res, 401, JSON.stringify(rpcError(null, -32000, `Missing or wrong ${what} token.`)));
}

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Accept, Authorization, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID');
  res.setHeader('Access-Control-Expose-Headers', 'Mcp-Session-Id');
}

function send(res, status, body, type) {
  if (res.writableEnded) return;
  cors(res);
  if (body === undefined || body === '') { res.writeHead(status); return res.end(); }
  res.writeHead(status, { 'Content-Type': type || 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function rpcError(id, code, message) {
  return { jsonrpc: '2.0', id: id === undefined ? null : id, error: { code, message } };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0;
    req.on('data', (c) => { size += c.length; if (size > MAX_BODY) { reject(new Error('too large')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/** Messages that carry an id and a method are requests and need an answer from the phone. */
function needsAnswer(message) {
  return message && typeof message === 'object' && typeof message.method === 'string' && Object.prototype.hasOwnProperty.call(message, 'id');
}

function phoneOnline(s) { return Date.now() - s.lastSeen < PHONE_ONLINE_MS; }

// ---------------------------------------------------------------- AI side

async function handleMcp(req, res, s) {
  if (req.method === 'GET') { res.setHeader('Allow', 'POST'); return send(res, 405, JSON.stringify(rpcError(null, -32000, 'This relay answers POST only.'))); }
  if (req.method === 'DELETE') return send(res, 200);
  if (req.method !== 'POST') return send(res, 405);

  let text;
  try { text = await readBody(req); } catch (e) { return send(res, 413, JSON.stringify(rpcError(null, -32600, 'Request too large.'))); }
  let parsed;
  try { parsed = JSON.parse(text); } catch (e) { return send(res, 400, JSON.stringify(rpcError(null, -32700, 'Invalid JSON.'))); }

  const list = Array.isArray(parsed) ? parsed : [parsed];
  const requests = list.filter(needsAnswer);
  if (requests.length === 0) return send(res, 202); // notifications and responses need no answer

  if (!phoneOnline(s)) {
    const message = 'Studio Mobile is not connected to this relay. Open the app, go to MCP and tap Connect.';
    const errors = requests.map((m) => rpcError(m.id, -32000, message));
    return send(res, 200, JSON.stringify(Array.isArray(parsed) ? errors : errors[0]));
  }
  if (s.queue.length >= MAX_QUEUE) return send(res, 503, JSON.stringify(rpcError(null, -32000, 'The phone has too many pending requests.')));

  const id = crypto.randomUUID();
  const entry = { id, body: text, res, timer: null, delivered: false };
  entry.timer = setTimeout(() => {
    s.pending.delete(id);
    s.queue = s.queue.filter((q) => q.id !== id);
    const errors = requests.map((m) => rpcError(m.id, -32001, 'The phone did not answer in time.'));
    send(res, 200, JSON.stringify(Array.isArray(parsed) ? errors : errors[0]));
  }, AI_TIMEOUT_MS);
  s.pending.set(id, entry);
  res.on('close', () => { if (!res.writableEnded) { clearTimeout(entry.timer); s.pending.delete(id); s.queue = s.queue.filter((q) => q.id !== id); } });

  s.queue.push(entry);
  flush(s);
}

// ---------------------------------------------------------------- phone side

function flush(s) {
  if (!s.waiting || s.queue.length === 0) return;
  const entry = s.queue.shift();
  const waiter = s.waiting; s.waiting = null;
  clearTimeout(waiter.timer);
  s.lastSeen = Date.now();
  entry.delivered = true;
  cors(waiter.res);
  waiter.res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'X-Relay-Id': entry.id, 'Cache-Control': 'no-store' });
  waiter.res.end(entry.body);
}

function handlePoll(req, res, s, url) {
  s.lastSeen = Date.now();
  if (s.waiting) { // the phone reconnected: release the old poll
    clearTimeout(s.waiting.timer); send(s.waiting.res, 204); s.waiting = null;
  }
  const wait = Math.min(Math.max(Number(url.searchParams.get('wait')) || 25, 1), POLL_MAX_MS / 1000) * 1000;
  const waiter = { res, timer: setTimeout(() => { if (s.waiting === waiter) s.waiting = null; s.lastSeen = Date.now(); send(res, 204); }, wait) };
  res.on('close', () => { clearTimeout(waiter.timer); if (s.waiting === waiter) s.waiting = null; s.lastSeen = Date.now(); });
  s.waiting = waiter;
  flush(s);
}

async function handleReply(req, res, s, id) {
  if (req.method !== 'POST') return send(res, 405);
  s.lastSeen = Date.now();
  let text;
  try { text = await readBody(req); } catch (e) { return send(res, 413); }
  const entry = s.pending.get(id);
  if (!entry) return send(res, 404, JSON.stringify(rpcError(null, -32000, 'Unknown or expired request id.')));
  s.pending.delete(id);
  clearTimeout(entry.timer);
  if (text.trim() === '') send(entry.res, 202); else send(entry.res, 200, text);
  send(res, 204);
}

// ---------------------------------------------------------------- routing

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://relay.local');
  if (req.method === 'OPTIONS') return send(res, 204);
  if (url.pathname === '/' || url.pathname === '/health') return send(res, 200, 'Studio Mobile MCP relay is running.\n', 'text/plain; charset=utf-8');

  const match = url.pathname.match(/^\/s\/([^/]+)\/(mcp|phone\/poll|phone\/reply\/([^/]+))\/?$/);
  if (!match) return send(res, 404, JSON.stringify(rpcError(null, -32000, 'Not found. Use /s/<secret>/mcp.')));
  const secret = decodeURIComponent(match[1]);
  if (!secretAllowed(secret)) return send(res, 403, JSON.stringify(rpcError(null, -32000, 'This secret is not allowed on this relay.')));
  const s = getSession(secret);

  if (match[2] === 'mcp') {
    if (req.method !== 'OPTIONS' && !tokenOk(req, AI_TOKEN)) return unauthorized(res, 'AI');
    return void handleMcp(req, res, s);
  }
  if (!tokenOk(req, PHONE_TOKEN)) return unauthorized(res, 'phone');
  if (match[2] === 'phone/poll') return req.method === 'GET' ? handlePoll(req, res, s, url) : send(res, 405);
  return void handleReply(req, res, s, decodeURIComponent(match[3]));
});

setInterval(() => { // forget sessions nobody has used for an hour
  const cutoff = Date.now() - 3600000;
  for (const [key, s] of sessions) if (s.lastSeen < cutoff && s.pending.size === 0 && !s.waiting) sessions.delete(key);
}, 600000).unref();

server.keepAliveTimeout = 65000;
server.listen(PORT, () => console.log(`Studio Mobile MCP relay listening on :${PORT}`));
