'use strict';
// Hermes for Pebble: a plugin that sends messages to Hermes Agent sessions from Index and any
// Pebble app, and serves the session list and a small status reading.
//
// It talks to the Hermes dashboard the same way the Wristbound Android companion does
// (docs/HERMES_API.md): REST for the session list and transcript, and one short-lived gateway
// WebSocket per send (session.resume or session.create, then prompt.submit). Plugins only run per
// request, so nothing here waits for Hermes' answer: a send returns once Hermes has accepted the
// prompt, and the turn carries on server-side after the socket closes.

var CREDENTIALS = 'hermes.credentials.v1'; // Pebble.secureStorage: dashboard tokens
var SETTINGS = 'hermes.settings.v1';       // localStorage: address, default destination
var SESSIONS = 'hermes.sessions.v1';       // localStorage: last session list, for name lookups
var REFRESH_LEASE = 'hermes.refresh.v1';   // localStorage: who is rotating the refresh token
var HEADLESS_REDIRECT = 'http://127.0.0.1/wristbound'; // never visited; the code comes back in JSON
var SESSION_LIST_TTL_MS = 2 * 60 * 1000;
var REFRESH_MARGIN_S = 120;
var LEASE_MS = 15000;
var MAX_MESSAGE = 8000;
// How long a send waits on the gateway. Before prompt.submit leaves the phone a timeout means
// "not sent"; after it, "uncertain". Plugin runs are short, so these stay well inside them.
var TIMEOUTS = { ready: 10000, call: 15000, submit: 20000 };
// The companion Hermes plugin (hermes-plugin/wristbound-voice) that speaks replies on request.
var VOICE_PLUGIN = 'wristbound-voice';
var MESSAGING_SOURCES = ['telegram', 'discord', 'slack', 'whatsapp', 'signal', 'matrix', 'email', 'sms'];
var NEW = ['new', 'new session', 'new chat', 'new task'];
// The spoken command that starts a fresh session when it leads a capture. Settings can change it.
var DEFAULT_FRESH_PHRASE = 'New Hermes session';
var MAX_FRESH_PHRASE = 60;
var PHRASE_GAP = '[\\s,.:;!?]+'; // transcripts vary the punctuation between spoken words

// A tidy phrase from settings input, or null when it cannot work as a command.
function cleanFreshPhrase(value) {
  var words = String(value == null ? '' : value).split(/[\s,.:;!?]+/).filter(Boolean);
  var phrase = words.join(' ');
  if (!phrase || phrase.length > MAX_FRESH_PHRASE || !/[A-Za-z0-9\u00c0-\uffff]/.test(phrase)) return null;
  return phrase;
}

// Matches the phrase at the start of a message, then an optional "then", up to the message itself.
function freshPattern(phrase) {
  var words = (cleanFreshPhrase(phrase) || DEFAULT_FRESH_PHRASE).split(' ').map(function (w) {
    return w.replace(/[.*+?^${}()|[\]\\\/-]/g, '\\$&');
  });
  return new RegExp('^' + words.join(PHRASE_GAP) +
    '(?:(?:\\s*[,:.!?;]\\s*(?:then\\b\\s*[,:;]?\\s*)?)|\\s+then\\b\\s*[,:;]?\\s*|$)', 'i');
}

function PluginError(code, message) {
  var error = new Error(message);
  error.code = code;
  return error;
}

// --- Small pure helpers -------------------------------------------------------------------------

function normaliseBase(raw) {
  var text = String(raw || '').trim();
  if (!text) throw PluginError('INVALID_ARGS', 'Enter the dashboard address');
  if (!/^[a-z]+:\/\//i.test(text)) text = 'https://' + text;
  var match = /^(https?):\/\/([^\/?#@\s]+)(\/[^?#\s]*)?$/i.exec(text);
  if (!match) throw PluginError('INVALID_ARGS', 'That is not a dashboard address');
  var scheme = match[1].toLowerCase();
  var host = match[2].toLowerCase();
  var hostname = host.replace(/:\d+$/, '');
  // Same rule as the companion: plain HTTP only inside a tailnet, where WireGuard encrypts it.
  if (scheme === 'http' && !/\.ts\.net$/.test(hostname)) {
    throw PluginError('INVALID_ARGS', 'Use https, or a *.ts.net tailnet address');
  }
  var path = (match[3] || '').replace(/\/+$/, '');
  return scheme + '://' + host + path;
}

function hostOf(base) { return base.replace(/^https?:\/\//, '').replace(/[\/:].*$/, ''); }

function base64Url(bytes) {
  var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  var out = '';
  for (var i = 0; i < bytes.length; i += 3) {
    var n = (bytes[i] << 16) | ((bytes[i + 1] || 0) << 8) | (bytes[i + 2] || 0);
    out += alphabet[(n >> 18) & 63] + alphabet[(n >> 12) & 63];
    if (i + 1 < bytes.length) out += alphabet[(n >> 6) & 63];
    if (i + 2 < bytes.length) out += alphabet[n & 63];
  }
  return out;
}

// SHA-256, for the PKCE challenge on hosts without crypto.subtle.
function sha256(ascii) {
  var K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
  var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var bytes = [];
  for (var c = 0; c < ascii.length; c++) bytes.push(ascii.charCodeAt(c) & 0xff);
  var bitLength = bytes.length * 8;
  bytes.push(0x80);
  while (bytes.length % 64 !== 56) bytes.push(0);
  for (var s = 56; s >= 0; s -= 8) bytes.push(s >= 32 ? 0 : (bitLength >>> s) & 0xff);
  var w = new Array(64);
  for (var off = 0; off < bytes.length; off += 64) {
    for (var t = 0; t < 16; t++) {
      w[t] = (bytes[off + 4 * t] << 24) | (bytes[off + 4 * t + 1] << 16) | (bytes[off + 4 * t + 2] << 8) | bytes[off + 4 * t + 3];
    }
    for (t = 16; t < 64; t++) {
      var a0 = w[t - 15], a1 = w[t - 2];
      var s0 = ((a0 >>> 7) | (a0 << 25)) ^ ((a0 >>> 18) | (a0 << 14)) ^ (a0 >>> 3);
      var s1 = ((a1 >>> 17) | (a1 << 15)) ^ ((a1 >>> 19) | (a1 << 13)) ^ (a1 >>> 10);
      w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
    }
    var a = H[0], b = H[1], cc = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
    for (t = 0; t < 64; t++) {
      var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      var ch = (e & f) ^ (~e & g);
      var t1 = (h + S1 + ch + K[t] + w[t]) | 0;
      var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      var maj = (a & b) ^ (a & cc) ^ (b & cc);
      var t2 = (S0 + maj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = cc; cc = b; b = a; a = (t1 + t2) | 0;
    }
    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + cc) | 0; H[3] = (H[3] + d) | 0;
    H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
  }
  var out = [];
  for (var i = 0; i < 8; i++) out.push((H[i] >>> 24) & 0xff, (H[i] >>> 16) & 0xff, (H[i] >>> 8) & 0xff, H[i] & 0xff);
  return out;
}

// Set-Cookie lines -> [{ name, value, maxAge }]. Hosts differ in how they expose them: Node and
// browsers have getSetCookie(), the Pebble app's fetch repeats the header (forEach) and joins them
// with ", " in get(), which also splits "Expires=Thu, 01 Jan" dates, hence the lookahead.
function setCookies(response) {
  var lines = [];
  var headers = response && response.headers;
  if (!headers) return [];
  if (typeof headers.getSetCookie === 'function') lines = headers.getSetCookie();
  if (!lines.length && typeof headers.forEach === 'function') {
    headers.forEach(function (value, name) { if (String(name).toLowerCase() === 'set-cookie') lines.push(value); });
  }
  if (lines.length === 1 || (!lines.length && typeof headers.get === 'function' && headers.get('set-cookie'))) {
    lines = (lines[0] || headers.get('set-cookie')).split(/,(?=\s*[A-Za-z0-9_\-.]+=)/);
  }
  return lines.map(function (line) {
    var parts = String(line).split(';');
    var pair = parts[0].trim();
    var i = pair.indexOf('=');
    if (i <= 0) return null;
    var maxAge = null;
    parts.slice(1).forEach(function (attr) {
      var m = /^\s*max-age\s*=\s*(\d+)/i.exec(attr);
      if (m) maxAge = Number(m[1]);
    });
    return { name: pair.slice(0, i), value: pair.slice(i + 1), maxAge: maxAge };
  }).filter(Boolean);
}

// "a=1; Path=/; HttpOnly, b=2; Expires=Thu, 01 Jan 2027 00:00:00 GMT" -> "a=1; b=2"
function cookieHeader(response) {
  return setCookies(response).map(function (c) { return c.name + '=' + c.value; }).join('; ');
}

// The dashboard's session cookie, with or without a __Host-/__Secure- prefix or gateway suffix.
function sessionCookie(cookies, bare) {
  var pattern = new RegExp('^(?:__Host-|__Secure-)?' + bare + '(?:_[A-Za-z0-9-]+)?$');
  return cookies.filter(function (c) { return pattern.test(c.name) && c.value; })[0] || null;
}

function cleanText(text) {
  return String(text || '')
    .replace(/MEDIA:\S+/g, '')
    .replace(/\[\[audio_as_voice\]\]/g, '')
    .replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}

function messageText(row) {
  var content = row && row.content;
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content.map(function (part) { return part && (part.text || (typeof part === 'string' ? part : '')) || ''; }).join('');
  }
  return '';
}

function sessionTitle(row) {
  return String(row.title || row.preview || 'Untitled').replace(/\s+/g, ' ').trim().slice(0, 80);
}

function isMessagingSource(source) { return MESSAGING_SOURCES.indexOf(String(source || '').toLowerCase()) >= 0; }

// --- The service ---------------------------------------------------------------------------------

function createService(env) {
  var secure = env.secure, local = env.local, http = env.fetch, Socket = env.WebSocket;
  var now = env.now || Date.now;
  var timeouts = Object.assign({}, TIMEOUTS, env.timeouts || {});
  var later = env.setTimeout || setTimeout, cancelLater = env.clearTimeout || clearTimeout;

  function readLocal(key) { try { return JSON.parse(local.getItem(key)) || null; } catch (e) { return null; } }
  function writeLocal(key, value) {
    try { if (value == null) local.removeItem(key); else local.setItem(key, JSON.stringify(value)); } catch (e) {}
  }
  async function credentials() {
    try { return JSON.parse(await secure.getItem(CREDENTIALS)) || null; } catch (e) { return null; }
  }
  async function saveCredentials(value) {
    if (value == null) await secure.removeItem(CREDENTIALS);
    else await secure.setItem(CREDENTIALS, JSON.stringify(value));
  }
  function settings() { return readLocal(SETTINGS) || {}; }

  async function request(base, path, init) {
    var response;
    try {
      response = await http(base + path, init || {});
    } catch (e) {
      if (e && e.name === 'NotAllowedError') {
        throw PluginError('PERMISSION_DENIED', 'Allow Hermes to connect to ' + hostOf(base) + ' in the Pebble app');
      }
      throw PluginError('PLUGIN_UNAVAILABLE', 'Could not reach Hermes at ' + hostOf(base));
    }
    return response;
  }
  async function readJson(response, what) {
    try { return await response.json(); } catch (e) { throw PluginError('UNKNOWN', 'Hermes sent an unreadable ' + what); }
  }

  // --- Sign-in ---

  async function signInWithToken(base, token) {
    token = String(token || '').trim();
    if (!token || /[\r\n]/.test(token)) throw PluginError('INVALID_ARGS', 'Enter the dashboard session token');
    var response = await request(base, '/api/sessions?limit=1', { headers: { 'X-Hermes-Session-Token': token } });
    if (response.status === 401 || response.status === 403) throw PluginError('AUTH_REQUIRED', 'Hermes did not accept that token');
    if (response.status !== 200) throw PluginError('UNKNOWN', 'Hermes answered ' + response.status);
    return { base: base, kind: 'token', token: token };
  }

  async function pickPasswordProvider(base, wanted) {
    var response = await request(base, '/api/auth/providers', {});
    if (response.status !== 200) throw PluginError('UNKNOWN', 'Hermes did not list its sign-in providers');
    var providers = ((await readJson(response, 'provider list')).providers || []);
    var usable = providers.filter(function (p) { return p && p.supports_password; });
    var chosen = wanted ? usable.filter(function (p) { return p.name === wanted; })[0] : usable[0];
    if (!chosen) {
      throw PluginError('AUTH_REQUIRED', usable.length || !providers.length
        ? 'Hermes has no password sign-in by that name'
        : 'This dashboard signs in through a browser; use the session token instead');
    }
    return chosen.name;
  }

  // The companion's headless native flow (DashboardAuth.signInWithPassword): authorize with PKCE,
  // post the password with the cookie authorize set, then redeem the code from `next`.
  async function signInWithPassword(base, username, password, wantedProvider, entropy) {
    if (!username || !password) throw PluginError('INVALID_ARGS', 'Enter your Hermes username and password');
    if (base.indexOf('https://') !== 0) throw PluginError('INVALID_ARGS', 'Password sign-in requires HTTPS');
    if (!Array.isArray(entropy) || entropy.length !== 56 || entropy.some(function (v) { return !Number.isInteger(v) || v < 0 || v > 255; })) {
      throw PluginError('INVALID_ARGS', 'Secure randomness is unavailable; reopen settings and try again');
    }
    var verifier = base64Url(entropy.slice(0, 32));
    var state = base64Url(entropy.slice(32));
    var provider = await pickPasswordProvider(base, wantedProvider);
    var challenge = base64Url(sha256(verifier));
    var query = '?code_challenge=' + challenge + '&code_challenge_method=S256' +
      '&redirect_uri=' + encodeURIComponent(HEADLESS_REDIRECT) + '&state=' + state +
      '&provider=' + encodeURIComponent(provider) + '&response_mode=json';
    var authorize = await request(base, '/auth/native/authorize' + query, {});
    if (authorize.status !== 200) throw PluginError('UNKNOWN', 'Hermes refused to start sign-in (' + authorize.status + ')');
    var authorization;
    try { authorization = await readJson(authorize, 'native password authorization'); } catch (e) {}
    if (!authorization || authorization.native_password !== true) {
      throw PluginError('UNKNOWN', 'This dashboard does not support Pebble password sign-in. See the Hermes plugin setup guide.');
    }
    var cookie = cookieHeader(authorize);
    var headers = { 'Content-Type': 'application/json' };
    if (cookie) headers.Cookie = cookie;
    var login = await request(base, '/auth/password-login', {
      method: 'POST', headers: headers,
      body: JSON.stringify({ provider: provider, username: String(username), password: String(password), next: '', native_only: true }),
    });
    if (login.status === 401) throw PluginError('AUTH_REQUIRED', 'Wrong username or password');
    if (login.status === 429) throw PluginError('RATE_LIMITED', 'Too many attempts; wait a minute');
    if (login.status !== 200) throw PluginError('UNKNOWN', 'Sign-in failed (' + login.status + ')');
    var next = String((await readJson(login, 'sign-in answer')).next || '');
    if (next.indexOf(HEADLESS_REDIRECT) !== 0) {
      throw PluginError('UNKNOWN', 'Hermes did not return a native sign-in code; sign in with the session token instead');
    }
    var params = {};
    next.replace(/^[^?]*\?/, '').split('&').forEach(function (pair) {
      var i = pair.indexOf('=');
      if (i > 0) params[decodeURIComponent(pair.slice(0, i))] = decodeURIComponent(pair.slice(i + 1));
    });
    if (!params.code) throw PluginError('UNKNOWN', 'Sign-in was cancelled');
    if (params.state !== state) throw PluginError('UNKNOWN', 'Sign-in response did not match; try again');
    var token = await request(base, '/auth/native/token', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: params.code, code_verifier: verifier }),
    });
    if (token.status !== 200) throw PluginError('AUTH_REQUIRED', 'Sign-in expired; try again');
    var tokens = await readJson(token, 'token answer');
    if (!tokens.access_token) throw PluginError('UNKNOWN', 'Hermes returned no access token');
    return {
      base: base, kind: 'native', provider: tokens.provider || provider, username: String(username),
      access_token: tokens.access_token, refresh_token: tokens.refresh_token || '', expires_at: tokens.expires_at || 0,
    };
  }

  // Hermes revokes a session whose refresh token is replayed, and two plugin runs can overlap, so a
  // run only refreshes while it holds the lease; the other one keeps the access token it has.
  async function refresh(current) {
    if (!current.refresh_token) return null;
    var lease = readLocal(REFRESH_LEASE);
    if (lease && now() - lease.at < LEASE_MS) return current;
    // Lease IDs distinguish overlapping runs, not credentials or PKCE state.
    var mine = { at: now(), id: String(now()) + '-' + Math.random().toString(36).slice(2) };
    writeLocal(REFRESH_LEASE, mine);
    if ((readLocal(REFRESH_LEASE) || {}).id !== mine.id) return current;
    try {
      var latest = await credentials();
      if (latest && latest.refresh_token !== current.refresh_token) return latest; // someone else rotated
      var response = await request(current.base, '/auth/native/refresh', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: current.refresh_token, provider: current.provider }),
      });
      if (response.status === 401) {
        var expired = Object.assign({}, current, { access_token: '', refresh_token: '', expired: true });
        await saveCredentials(expired);
        return null;
      }
      if (response.status !== 200) return current; // 5xx: keep the tokens, try next time
      var tokens = await readJson(response, 'refresh answer');
      var next = Object.assign({}, current, {
        access_token: tokens.access_token, refresh_token: tokens.refresh_token || current.refresh_token,
        expires_at: tokens.expires_at || 0,
      });
      await saveCredentials(next);
      return next;
    } finally {
      writeLocal(REFRESH_LEASE, null);
    }
  }

  async function signedIn() {
    var current = await credentials();
    if (!current || current.expired) throw PluginError('AUTH_REQUIRED', 'Sign in to Hermes in the plugin settings');
    if (current.kind === 'native' && current.expires_at && current.expires_at - now() / 1000 < REFRESH_MARGIN_S) {
      current = await refresh(current);
      if (!current) throw PluginError('AUTH_REQUIRED', 'Hermes sign-in expired; sign in again in the plugin settings');
    }
    return current;
  }

  function authHeaders(current, extra) {
    var headers = Object.assign({}, extra || {});
    if (current.kind === 'token') headers['X-Hermes-Session-Token'] = current.token;
    else headers.Authorization = 'Bearer ' + current.access_token;
    return headers;
  }

  async function api(path, init) {
    var current = await signedIn();
    init = init || {};
    var response = await request(current.base, path, Object.assign({}, init, { headers: authHeaders(current, init.headers) }));
    if (response.status === 401 && current.kind === 'native') {
      var renewed = await refresh(current);
      if (!renewed || renewed === current) throw PluginError('AUTH_REQUIRED', 'Hermes sign-in expired; sign in again in the plugin settings');
      response = await request(renewed.base, path, Object.assign({}, init, { headers: authHeaders(renewed, init.headers) }));
    }
    if (response.status === 401 || response.status === 403) throw PluginError('AUTH_REQUIRED', 'Hermes rejected the sign-in');
    if (response.status < 200 || response.status >= 300) throw PluginError('UNKNOWN', 'Hermes answered ' + response.status);
    return readJson(response, 'answer');
  }

  // --- Sessions ---

  async function listSessions(limit) {
    var body = await api('/api/sessions?order=recent&limit=' + Math.max(1, Math.min(limit || 30, 50)));
    var rows = (body.sessions || []).filter(function (row) { return row && row.id && !row.archived; });
    writeLocal(SESSIONS, { at: now(), rows: rows.map(function (row) {
      return { id: row.id, title: sessionTitle(row), source: row.source || '' };
    }) });
    return rows;
  }
  async function knownSessions() {
    var cached = readLocal(SESSIONS);
    if (cached && now() - cached.at < SESSION_LIST_TTL_MS) return cached.rows;
    await listSessions(50);
    return (readLocal(SESSIONS) || { rows: [] }).rows;
  }

  // chat_id: "new", an exact session id, an exact title, or a unique part of a title.
  async function resolveTarget(chatId) {
    var wanted = chatId == null ? '' : String(chatId).trim();
    if (!wanted) {
      var prefs = settings();
      var preferred = prefs.destination || 'new';
      if (preferred === 'ongoing') {
        if (!prefs.ongoingSessionId) return null;
        return { id: prefs.ongoingSessionId, title: 'your ongoing Index conversation', source: 'webui', ongoing: true };
      }
      if (preferred === 'latest') {
        // Read fresh on every send. The cached selector list can miss a chat just opened elsewhere.
        var recent = await listSessions(50);
        var latest = recent.find(function (row) {
          var source = String(row.source || '').toLowerCase();
          return !row.hidden && source !== 'cron' && source !== 'subagent' &&
            !(prefs.messagingReadOnly && isMessagingSource(source));
        });
        if (!latest) throw PluginError('INVALID_ARGS', 'No eligible recent Hermes conversation; ask for a new session first');
        return { id: latest.id, title: sessionTitle(latest), source: latest.source || '' };
      }
      if (preferred === 'new') return null;
      wanted = preferred;
    }
    if (NEW.indexOf(wanted.toLowerCase()) >= 0) return null;
    var rows = await knownSessions();
    var lower = wanted.toLowerCase();
    var exact = rows.filter(function (row) { return row.id === wanted; })[0] ||
      rows.filter(function (row) { return row.title.toLowerCase() === lower; })[0];
    if (exact) return exact;
    var partial = rows.filter(function (row) { return row.title.toLowerCase().indexOf(lower) >= 0; });
    if (partial.length === 1) return partial[0];
    if (partial.length > 1) {
      throw PluginError('INVALID_ARGS', 'More than one Hermes chat matches "' + wanted + '": ' +
        partial.slice(0, 4).map(function (row) { return row.title; }).join(', '));
    }
    throw PluginError('INVALID_ARGS', 'No recent Hermes chat matches "' + wanted + '"');
  }

  // --- Gateway socket ---

  async function socketUrl(current) {
    var wsBase = current.base.replace(/^http/, 'ws') + '/api/ws';
    if (current.kind === 'token') return wsBase + '?token=' + encodeURIComponent(current.token);
    var body = await api('/api/auth/ws-ticket', { method: 'POST' });
    if (!body.ticket) throw PluginError('UNKNOWN', 'Hermes returned no socket ticket');
    return wsBase + '?ticket=' + encodeURIComponent(body.ticket);
  }

  // One JSON-RPC conversation: open, wait for gateway.ready, run `steps`, close. `sent` flips once
  // prompt.submit has left the phone; after that a failure is "uncertain", never "not sent".
  function gatewaySession(url, steps) {
    return new Promise(function (resolve, reject) {
      var ws;
      try { ws = new Socket(url); } catch (e) { reject(PluginError('PLUGIN_UNAVAILABLE', 'Could not open the Hermes gateway')); return; }
      var pending = {};
      var nextId = 1;
      var finished = false;
      var state = { sent: false };
      // Nothing from Hermes at all: never sent.
      var readyTimer = later(function () {
        finish(PluginError('PLUGIN_UNAVAILABLE', 'Hermes did not answer in time; not sent'));
      }, timeouts.ready);
      function finish(error, value) {
        if (finished) return;
        finished = true;
        cancelLater(readyTimer);
        try { ws.close(); } catch (e) {}
        Object.keys(pending).forEach(function (id) { cancelLater(pending[id].timer); pending[id].reject(PluginError('UNKNOWN', 'closed')); });
        if (error) {
          // Whether prompt.submit had left the phone when this failed, for the caller's clean-up.
          if (error.sent === undefined) error.sent = state.sent;
          reject(error);
        } else resolve(value);
      }
      function call(method, params, isSubmit) {
        return new Promise(function (ok, fail) {
          var id = 'p' + (nextId++);
          var timer = later(function () {
            delete pending[id];
            finish(state.sent
              ? PluginError('UNKNOWN', 'Hermes did not confirm the message in time; check Hermes before sending again')
              : PluginError('PLUGIN_UNAVAILABLE', 'Hermes did not answer in time; not sent'));
          }, isSubmit ? timeouts.submit : timeouts.call);
          pending[id] = { resolve: ok, reject: fail, timer: timer };
          ws.send(JSON.stringify({ jsonrpc: '2.0', id: id, method: method, params: params || {} }));
          if (isSubmit) state.sent = true;
        });
      }
      function lostConnection(message) {
        finish(state.sent
          ? PluginError('UNKNOWN', 'The connection dropped after sending; check Hermes before sending again')
          : PluginError('PLUGIN_UNAVAILABLE', message + '; not sent'));
      }
      ws.onmessage = function (event) {
        var frame;
        try { frame = JSON.parse(typeof event.data === 'string' ? event.data : String(event.data)); } catch (e) { return; }
        if (frame.method === 'event') {
          if (frame.params && frame.params.type === 'gateway.ready') {
            cancelLater(readyTimer);
            Promise.resolve().then(function () { return steps(call, state); })
              .then(function (value) { finish(null, value); }, function (error) { finish(error); });
          }
          return;
        }
        if (frame.method && frame.id != null) {
          // A server request (approval, clarify...). This client shows nothing, so decline; Hermes
          // asks the other clients, and gives up only if none answers.
          ws.send(JSON.stringify({ jsonrpc: '2.0', id: frame.id, error: { code: 4404, message: 'not shown on this client' } }));
          return;
        }
        var waiter = frame.id != null && pending[frame.id];
        if (!waiter) return;
        delete pending[frame.id];
        cancelLater(waiter.timer);
        if (frame.error) {
          var rpc = PluginError(state.sent ? 'UNKNOWN' : 'PLUGIN_UNAVAILABLE', (frame.error.message || 'Hermes refused') + (state.sent ? '' : '; not sent'));
          rpc.rpcCode = frame.error.code;
          rpc.refused = true;
          waiter.reject(rpc);
        } else {
          waiter.resolve(frame.result || {});
        }
      };
      ws.onerror = function () { lostConnection('Could not reach the Hermes gateway'); };
      ws.onclose = function (event) {
        if (event && (event.code === 4401 || event.code === 4403 || event.code === 1008)) {
          finish(PluginError('AUTH_REQUIRED', 'Hermes rejected the sign-in'));
          return;
        }
        lostConnection('The Hermes gateway closed');
      };
    });
  }

  // The arm's id when armed, false when the Hermes plugin is not there (or refused), so the send
  // goes ahead as text. A sign-in problem still stops the send, as it would at the gateway.
  // [sessionId], when the chat is known, limits the arm to that chat.
  async function armVoiceReply(message, sessionId) {
    try {
      var body = { text: message };
      if (sessionId) body.session_id = sessionId;
      var armed = await api('/api/plugins/' + VOICE_PLUGIN + '/arm', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      if (!armed || armed.armed !== true) return false;
      return armed.arm_id != null ? armed.arm_id : true;
    } catch (e) {
      if (e.code === 'AUTH_REQUIRED') throw e;
      return false;
    }
  }

  // The message never reached Hermes: forget its arm, so a later message with the same words
  // is not spoken by mistake. Best effort; an arm left behind expires on its own.
  async function cancelVoiceArm(armId) {
    if (typeof armId !== 'number' && typeof armId !== 'string') return;
    try {
      await api('/api/plugins/' + VOICE_PLUGIN + '/arms/' + encodeURIComponent(String(armId)), { method: 'DELETE' });
    } catch (e) {}
  }

  async function sendToAgent(args) {
    var message = String((args && args.message) || '').trim();
    if (!message) throw PluginError('INVALID_ARGS', 'Nothing to send');
    if (message.length > MAX_MESSAGE) throw PluginError('INVALID_ARGS', 'That message is too long for Hermes from the watch');
    // Index can leave this spoken command in the transcript instead of setting chat_id.
    var phrase = cleanFreshPhrase(settings().freshPhrase) || DEFAULT_FRESH_PHRASE;
    var fresh = freshPattern(phrase).exec(message);
    if (fresh) {
      message = message.slice(fresh[0].length).trim();
      if (!message) throw PluginError('INVALID_ARGS', 'Say a message after “' + phrase + '”');
    }
    var startPrefs = settings();
    var target = await resolveTarget(fresh ? 'new' : args && args.chat_id);
    if (target && isMessagingSource(target.source) && settings().messagingReadOnly) {
      throw PluginError('INVALID_ARGS', '"' + target.title + '" is a ' + target.source + ' chat; reply there instead');
    }
    // Reply with voice: ask the wristbound-voice Hermes plugin to speak the reply to exactly this
    // message, then send it with the ordinary prompt.submit. Arming first means the turn is
    // never sent twice; if the plugin is missing the message still goes, as text only.
    var voice = settings().voiceReplies ? await armVoiceReply(message, target && target.id) : null;
    var outcome;
    try {
      outcome = await sendPrompt(target, message);
    } catch (error) {
      // Only when the message certainly did not go: after prompt.submit left, Hermes may run it.
      if (voice && (error.sent !== true || error.notSent)) await cancelVoiceArm(voice);
      throw error;
    }
    return receipt(target, message, startPrefs, outcome, voice);
  }

  async function sendPrompt(target, message) {
    var current = await signedIn();
    var url = await socketUrl(current);
    return gatewaySession(url, async function (call) {
      var runtime, stored;
      if (target) {
        var resumed = await call('session.resume', { session_id: target.id, omit_messages: true, inline_images: false });
        runtime = resumed.session_id;
        stored = target.id;
      } else {
        var created = await call('session.create', { source: 'webui' });
        runtime = created.session_id;
        stored = created.stored_session_id;
      }
      if (!runtime) throw PluginError('UNKNOWN', 'Hermes did not open the session; not sent');
      var ack = await call('prompt.submit', { session_id: runtime, text: message, queued: true }, true);
      if (ack.status === 'rejected') {
        var declined = PluginError('INVALID_ARGS', 'Hermes declined the message; not sent');
        declined.notSent = true;
        throw declined;
      }
      if (ack.status !== 'streaming' && ack.status !== 'queued') {
        throw PluginError('UNKNOWN', 'Hermes did not confirm delivery; check Hermes before sending again');
      }
      return { stored: stored, queued: ack.status === 'queued' };
    });
  }

  function receipt(target, message, startPrefs, outcome, voice) {
    if (!target && outcome.stored) {
      var savedSettings = settings();
      if (startPrefs.destination === 'ongoing' && savedSettings.destination === 'ongoing' &&
          savedSettings.base === startPrefs.base && savedSettings.ongoingSessionId === startPrefs.ongoingSessionId &&
          savedSettings.routingEpoch === startPrefs.routingEpoch) {
        savedSettings.ongoingSessionId = outcome.stored;
        writeLocal(SETTINGS, savedSettings);
      }
      var cached = readLocal(SESSIONS);
      if (cached) {
        cached.rows.unshift({ id: outcome.stored, title: message.slice(0, 60), source: 'webui' });
        writeLocal(SESSIONS, cached);
      }
    }
    var where = target ? (target.ongoing ? 'your ongoing Index conversation' : '"' + target.title + '"') : 'a new Hermes session';
    return {
      text: (outcome.queued ? 'Queued for ' : 'Sent to ') + where + '.' +
        (voice === false ? ' No voice reply: the Hermes voice plugin is not set up.' : ''),
      refreshed: ['agent/chats', 'hermes/status'],
      session_id: outcome.stored || null,
    };
  }

  async function latestReply(args) {
    var target = await resolveTarget(args && args.chat_id);
    if (!target) {
      var rows = await listSessions(1);
      if (!rows.length) throw PluginError('INVALID_ARGS', 'Hermes has no chats yet');
      target = { id: rows[0].id, title: sessionTitle(rows[0]) };
    }
    var body = await api('/api/sessions/' + encodeURIComponent(target.id) + '/messages?order=latest&limit=20&inline_images=false');
    var messages = (body.messages || []).slice().sort(function (a, b) { return (b.id || 0) - (a.id || 0); });
    var reply = messages.filter(function (row) { return row.role === 'assistant' && cleanText(messageText(row)); })[0];
    if (!reply) return { text: '"' + target.title + '" has no reply from Hermes yet.' };
    var text = cleanText(messageText(reply));
    if (text.length > 1500) text = text.slice(0, 1497) + '...';
    return { text: text, session_id: target.id };
  }

  // --- Sources ---

  function shortText(text) { return { shortText: { text: String(text) } }; }

  async function configuredSource() {
    var current = await credentials();
    var ok = !!(current && !current.expired);
    var name = ok ? 'Hermes (' + hostOf(current.base) + ')' : 'Hermes';
    return { validUntilMs: now() + 60000, instances: [{ instanceId: 'hermes', properties: {
      name: shortText(name),
      configured: { boolean: { value: ok }, shortText: { text: ok ? 'Ready' : 'Sign in' } },
    } }] };
  }

  async function chatsSource(request) {
    var rows = await listSessions(request && request.limit ? Math.min(request.limit, 50) : 30);
    return { validUntilMs: now() + 5 * 60000, instances: rows.map(function (row) {
      var title = sessionTitle(row);
      var properties = {
        name: shortText(title),
        title: { shortText: { text: title }, longText: { text: title } },
        // is_active: activity in the last five minutes, not necessarily a running turn.
        active: { boolean: { value: !!row.is_active }, shortText: { text: row.is_active ? 'Active' : 'Quiet' } },
      };
      if (row.preview) properties.preview = { longText: { text: cleanText(row.preview).slice(0, 200) } };
      if (row.last_active) properties.last_active = { timestamp: { value: Math.floor(Number(row.last_active)) } };
      return { instanceId: row.id, properties: properties };
    }) };
  }

  async function statusSource() {
    var rows = await listSessions(20);
    var active = rows.filter(function (row) { return row.is_active; }).length;
    var latest = rows[0];
    var properties = {
      active_chats: { numericValue: { value: active, min: 0 }, shortText: { text: active ? active + ' live' : 'Quiet' },
        longText: { text: active ? active + (active === 1 ? ' Hermes chat' : ' Hermes chats') + ' active in the last 5 min' : 'No Hermes activity in the last 5 min' } },
    };
    if (latest) {
      properties.latest_chat = { shortText: { text: sessionTitle(latest).slice(0, 12) }, longText: { text: sessionTitle(latest) } };
      if (latest.last_active) properties.last_active = { timestamp: { value: Math.floor(Number(latest.last_active)) } };
    }
    return { validUntilMs: now() + 2 * 60000, instances: [{ instanceId: 'hermes', properties: properties }] };
  }

  // --- Entry points ---

  async function action(requestBody) {
    var name = requestBody && requestBody.action;
    if (name === 'send_to_agent') return sendToAgent(requestBody.args || {});
    if (name === 'read_latest_reply') return latestReply(requestBody.args || {});
    throw PluginError('INVALID_REQUEST', 'Unknown action');
  }

  async function source(requestBody) {
    var key = requestBody && (requestBody.category + '/' + requestBody.item);
    if (key === 'agent/configured') return configuredSource();
    if (key === 'agent/chats') return chatsSource(requestBody);
    if (key === 'hermes/status') return statusSource();
    throw PluginError('PLUGIN_UNAVAILABLE', 'Unknown source');
  }

  async function status() {
    var current = await credentials();
    var s = settings();
    return {
      signedIn: !!(current && !current.expired), expired: !!(current && current.expired),
      base: current ? current.base : (s.base || ''), kind: current ? current.kind : null,
      username: current && current.username || null,
      destination: s.destination || 'new', ongoingSessionId: s.ongoingSessionId || null, messagingReadOnly: !!s.messagingReadOnly,
      voiceReplies: !!s.voiceReplies,
      freshPhrase: cleanFreshPhrase(s.freshPhrase) || DEFAULT_FRESH_PHRASE, defaultFreshPhrase: DEFAULT_FRESH_PHRASE,
    };
  }

  async function config(message) {
    var type = message && message.type;
    if (type === 'status') return status();
    if (type === 'connect') {
      var base = normaliseBase(message.base);
      if (!message.token && base.indexOf('https://') !== 0) throw PluginError('INVALID_ARGS', 'Password sign-in requires HTTPS');
      if (!message.token && (!Array.isArray(message.auth_entropy) || message.auth_entropy.length !== 56 || message.auth_entropy.some(function (v) { return !Number.isInteger(v) || v < 0 || v > 255; }))) {
        throw PluginError('INVALID_ARGS', 'Secure randomness is unavailable; reopen settings and try again');
      }
      var probe = await request(base, '/api/status', {});
      if (probe.status !== 200) throw PluginError('UNKNOWN', 'That address did not answer like a Hermes dashboard');
      var info = await readJson(probe, 'status');
      var next = message.token
        ? await signInWithToken(base, message.token)
        : info.auth_required === false
          ? (function () { throw PluginError('INVALID_ARGS', 'This dashboard has no sign-in page; paste its session token'); })()
          : await signInWithPassword(base, message.username, message.password, message.provider, message.auth_entropy);
      await saveCredentials(next);
      var s = settings();
      if (s.base !== base) s = { base: base, destination: 'new', ongoingSessionId: null };
      s.routingEpoch = String(now()) + '-' + Math.random().toString(36).slice(2);
      delete s.ongoingSessionId;
      writeLocal(SETTINGS, s);
      writeLocal(SESSIONS, null);
      return status();
    }
    if (type === 'disconnect') {
      await saveCredentials(null);
      var reset = settings();
      delete reset.ongoingSessionId;
      reset.routingEpoch = String(now()) + '-' + Math.random().toString(36).slice(2);
      writeLocal(SETTINGS, reset);
      writeLocal(SESSIONS, null);
      return status();
    }
    if (type === 'sessions') {
      var rows = await listSessions(30);
      return { sessions: rows.map(function (row) { return { id: row.id, title: sessionTitle(row), source: row.source || '' }; }) };
    }
    if (type === 'destination') {
      var dest = String(message.destination || 'new');
      if (['new', 'ongoing', 'latest'].indexOf(dest) < 0 && !/^[A-Za-z0-9_-]+$/.test(dest)) throw PluginError('INVALID_ARGS', 'Invalid destination');
      writeLocal(SETTINGS, Object.assign(settings(), { destination: dest }));
      return status();
    }
    if (type === 'messagingReadOnly') {
      writeLocal(SETTINGS, Object.assign(settings(), { messagingReadOnly: !!message.value }));
      return status();
    }
    if (type === 'voiceReplies') {
      writeLocal(SETTINGS, Object.assign(settings(), { voiceReplies: !!message.value }));
      return status();
    }
    if (type === 'freshPhrase') {
      // Blank restores the default, so the setting can always be put back.
      var blank = !String(message.value == null ? '' : message.value).trim();
      var phrase = blank ? DEFAULT_FRESH_PHRASE : cleanFreshPhrase(message.value);
      if (!phrase) throw PluginError('INVALID_ARGS', 'Use up to ' + MAX_FRESH_PHRASE + ' characters, with at least one letter or number');
      var saved = settings();
      if (phrase === DEFAULT_FRESH_PHRASE) delete saved.freshPhrase; else saved.freshPhrase = phrase;
      writeLocal(SETTINGS, saved);
      return status();
    }
    throw PluginError('INVALID_REQUEST', 'Unknown settings message');
  }

  return { action: action, source: source, config: config };
}

function safeError(error) {
  console.log('Hermes request failed: ' + (error && error.code ? error.code + ': ' + error.message : error && error.name || error));
  return error && error.code ? { code: error.code, message: error.message }
    : { code: 'UNKNOWN', message: 'Hermes request failed. If sending, check Hermes before sending again.' };
}

if (typeof Pebble !== 'undefined' && Pebble.registerActionHandler) {
  var service = createService({
    secure: Pebble.secureStorage, local: localStorage, fetch: fetch,
    WebSocket: typeof WebSocket !== 'undefined' ? WebSocket : null,
    crypto: typeof crypto !== 'undefined' ? crypto : null, now: Date.now,
  });
  Pebble.registerActionHandler(function (request, respond) {
    service.action(request).then(respond.ok, function (error) { var e = safeError(error); respond.error(e.code, e.message); });
  });
  Pebble.registerSourceHandler(function (request, respond) {
    service.source(request).then(respond.data, function (error) { var e = safeError(error); respond.error(e.code, e.message); });
  });
  if (Pebble.registerConfigHandler) {
    Pebble.registerConfigHandler(function (message, respond) {
      service.config(message).then(respond, function (error) { respond({ error: safeError(error).message }); });
    });
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { createService: createService, freshPattern: freshPattern, normaliseBase: normaliseBase, sha256: sha256, base64Url: base64Url, cookieHeader: cookieHeader, setCookies: setCookies };
}
