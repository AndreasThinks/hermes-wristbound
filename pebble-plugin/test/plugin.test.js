const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { createService, normaliseBase, sha256, base64Url, cookieHeader } = require(path.join(__dirname, '../src/plugin/index.js'));

const BASE = 'https://hermes.tail1234.ts.net:10443';
const json = (value, status = 200, headers = {}) => ({
  status, json: async () => value, text: async () => JSON.stringify(value),
  headers: { get: (name) => headers[name.toLowerCase()] || null },
});

// A scripted gateway socket: answers JSON-RPC calls from `handlers`, records what it was sent.
function socketClass(handlers, log) {
  return class FakeSocket {
    constructor(url) {
      this.url = url; log.push({ open: url });
      setImmediate(() => this.onmessage({ data: JSON.stringify({ jsonrpc: '2.0', method: 'event', params: { type: 'gateway.ready' } }) }));
    }
    send(raw) {
      const frame = JSON.parse(raw); log.push(frame);
      if (!frame.method) return;
      const handler = handlers[frame.method];
      setImmediate(() => {
        if (!handler) return this.onclose({ code: 1006 });
        const out = handler(frame.params, this);
        if (out === 'drop') return this.onclose({ code: 1006 });
        this.onmessage({ data: JSON.stringify(out.error ? { jsonrpc: '2.0', id: frame.id, error: out.error } : { jsonrpc: '2.0', id: frame.id, result: out }) });
      });
    }
    close() { this.closed = true; log.push({ close: true }); }
  };
}

function fixture({ routes = {}, gateway = {}, now = 1_800_000_000_000 } = {}) {
  const secure = new Map(), local = new Map(), calls = [], socketLog = [];
  const clock = { now };
  const allRoutes = {
    'GET /api/status': () => json({ auth_required: true }),
    'GET /api/auth/providers': () => json({ providers: [{ name: 'basic', display_name: 'Password', supports_password: true }] }),
    'GET /auth/native/authorize': () => json({}, 302, { 'set-cookie': 'hermes_pkce=abc123; Path=/; HttpOnly; SameSite=Lax' }),
    'POST /auth/password-login': (init, url, f) => {
      assert.equal(init.headers.Cookie, 'hermes_pkce=abc123');
      const state = f.lastAuthorize.searchParams.get('state');
      return json({ ok: true, next: 'http://127.0.0.1/wristbound?code=CODE1&state=' + state });
    },
    'POST /auth/native/token': (init) => {
      const body = JSON.parse(init.body);
      assert.equal(body.code, 'CODE1');
      return json({ access_token: 'AT1', refresh_token: 'RT1', expires_at: now / 1000 + 3600, provider: 'basic' });
    },
    'POST /auth/native/refresh': () => json({ access_token: 'AT2', refresh_token: 'RT2', expires_at: now / 1000 + 7200 }),
    'GET /api/sessions': () => json({ sessions: [
      { id: 's-groceries', title: 'Groceries', source: 'telegram', is_active: true, last_active: now / 1000 - 30, preview: 'Milk MEDIA:/x.ogg' },
      { id: 's-repo', title: 'Repo cleanup', source: 'cli', is_active: false, last_active: now / 1000 - 9000 },
      { id: 's-repo2', title: 'Repo docs', source: 'webui', is_active: false, last_active: now / 1000 - 9900 },
      { id: 's-old', title: 'Old', archived: true },
    ] }),
    'POST /api/auth/ws-ticket': () => json({ ticket: 'TICKET', ttl_seconds: 30 }),
    ...routes,
  };
  const f = { secure, local, calls, socketLog, clock };
  const fetch = async (url, init = {}) => {
    assert.ok(url.startsWith(BASE), 'unexpected host ' + url);
    const parsed = new URL(url);
    const key = (init.method || 'GET') + ' ' + parsed.pathname;
    calls.push({ key, url, init });
    if (parsed.pathname === '/auth/native/authorize') f.lastAuthorize = parsed;
    const handler = allRoutes[key];
    assert.ok(handler, 'unexpected ' + key);
    return handler(init, parsed, f);
  };
  const gatewayHandlers = {
    'session.resume': (p) => ({ session_id: 'rt-' + p.session_id, running: false }),
    'session.create': () => ({ session_id: 'rt-new', stored_session_id: 's-new' }),
    'prompt.submit': () => ({ status: 'streaming', user_row_id: '41' }),
    ...gateway,
  };
  f.service = createService({
    secure: { getItem: async (k) => secure.get(k) ?? null, setItem: async (k, v) => { secure.set(k, v); }, removeItem: async (k) => { secure.delete(k); } },
    local: { getItem: (k) => local.has(k) ? local.get(k) : null, setItem: (k, v) => local.set(k, String(v)), removeItem: (k) => local.delete(k) },
    fetch, WebSocket: socketClass(gatewayHandlers, socketLog), crypto: crypto.webcrypto, now: () => clock.now,
  });
  f.signIn = () => f.service.config({ type: 'connect', base: 'hermes.tail1234.ts.net:10443', username: 'andreas', password: 'pw' });
  return f;
}

test('sha256 and base64url match node for PKCE', () => {
  const verifier = base64Url(crypto.randomBytes(32));
  const expected = crypto.createHash('sha256').update(verifier).digest('base64url');
  assert.equal(base64Url(sha256(verifier)), expected);
  assert.equal(base64Url(sha256('')), crypto.createHash('sha256').update('').digest('base64url'));
});

test('addresses: https by default, http only inside a tailnet', () => {
  assert.equal(normaliseBase('hermes.tail1234.ts.net:10443/'), BASE);
  assert.equal(normaliseBase('http://hermes.tail1234.ts.net:9119'), 'http://hermes.tail1234.ts.net:9119');
  assert.throws(() => normaliseBase('http://192.168.1.4:9119'), /https/);
  assert.throws(() => normaliseBase('https://user@evil.example'), /not a dashboard/);
});

test('cookie header keeps name=value pairs from joined Set-Cookie', () => {
  const r = json({}, 302, { 'set-cookie': 'a=1; Path=/; Expires=Thu, 01 Jan 2027 00:00:00 GMT, b=2; HttpOnly' });
  assert.equal(cookieHeader(r), 'a=1; b=2');
});

test('password sign-in runs the native PKCE flow and keeps tokens only in secure storage', async () => {
  const f = fixture();
  const status = await f.signIn();
  assert.equal(status.signedIn, true);
  assert.equal(status.kind, 'native');
  const authorize = f.calls.find((c) => c.key === 'GET /auth/native/authorize');
  assert.equal(authorize.init.redirect, 'manual');
  const challenge = new URL(authorize.url).searchParams.get('code_challenge');
  const verifier = JSON.parse(f.calls.find((c) => c.key === 'POST /auth/native/token').init.body).code_verifier;
  assert.equal(crypto.createHash('sha256').update(verifier).digest('base64url'), challenge);
  assert.ok(!JSON.stringify([...f.local.values()]).includes('AT1'));
  assert.equal(JSON.parse(f.secure.get('hermes.credentials.v1')).refresh_token, 'RT1');
  assert.ok(!JSON.stringify(status).includes('AT1'));
});

test('when fetch hides the authorize cookie, the session cookies from the login answer are used', async () => {
  // The Pebble app's fetch follows the authorize redirect itself, so no PKCE cookie reaches us and
  // Hermes answers as for a browser login: session cookies on a 200.
  const f = fixture({ routes: {
    'GET /auth/native/authorize': () => json({}, 200),
    'POST /auth/password-login': (init) => {
      assert.equal(init.headers.Cookie, undefined);
      return json({ ok: true, next: '/' }, 200, { 'set-cookie':
        '__Host-hermes_session_at=AT-C; Max-Age=900; Path=/; Secure; HttpOnly, __Host-hermes_session_rt=RT-C; Max-Age=2592000; Path=/; Secure; HttpOnly, hermes_session_provider=basic; Path=/' });
    },
  } });
  const status = await f.signIn();
  assert.equal(status.signedIn, true);
  const saved = JSON.parse(f.secure.get('hermes.credentials.v1'));
  assert.equal(saved.access_token, 'AT-C');
  assert.equal(saved.refresh_token, 'RT-C');
  assert.equal(saved.expires_at, f.clock.now / 1000 + 900);
  await f.service.source({ category: 'agent', item: 'chats' });
  assert.equal(f.calls.at(-1).init.headers.Authorization, 'Bearer AT-C');
});

test('a login answer with neither a code nor a session says what to do', async () => {
  const f = fixture({ routes: { 'POST /auth/password-login': () => json({ ok: true, next: '/' }) } });
  await assert.rejects(f.signIn(), /session token/);
  assert.equal(f.secure.size, 0);
});

test('session-token dashboards sign in with the token', async () => {
  const f = fixture({ routes: { 'GET /api/status': () => json({ auth_required: false }) } });
  await assert.rejects(f.signIn(), /session token/);
  const status = await f.service.config({ type: 'connect', base: BASE, token: 'TOK' });
  assert.equal(status.kind, 'token');
  assert.equal(f.calls.at(-1).init.headers['X-Hermes-Session-Token'], 'TOK');
});

test('send_to_agent with no chat starts a new session and closes the socket after the ack', async () => {
  const f = fixture();
  await f.signIn();
  const result = await f.service.action({ action: 'send_to_agent', args: { message: 'Book the dentist' } });
  assert.equal(result.text, 'Sent to a new Hermes session.');
  assert.equal(result.session_id, 's-new');
  assert.equal(f.socketLog[0].open, 'wss://hermes.tail1234.ts.net:10443/api/ws?ticket=TICKET');
  const methods = f.socketLog.filter((x) => x.method).map((x) => x.method);
  assert.deepEqual(methods, ['session.create', 'prompt.submit']);
  const submit = f.socketLog.find((x) => x.method === 'prompt.submit');
  assert.deepEqual(submit.params, { session_id: 'rt-new', text: 'Book the dentist', queued: true });
  assert.ok(f.socketLog.at(-1).close);
});

test('chat_id resolves by id, exact title and unique partial title', async () => {
  const f = fixture();
  await f.signIn();
  assert.equal((await f.service.action({ action: 'send_to_agent', args: { message: 'x', chat_id: 'groceries' } })).text, 'Sent to "Groceries".');
  assert.equal((await f.service.action({ action: 'send_to_agent', args: { message: 'x', chat_id: 's-repo2' } })).session_id, 's-repo2');
  await assert.rejects(f.service.action({ action: 'send_to_agent', args: { message: 'x', chat_id: 'repo' } }), /More than one/);
  await assert.rejects(f.service.action({ action: 'send_to_agent', args: { message: 'x', chat_id: 'Old' } }), /No recent/);
  const resumes = f.socketLog.filter((x) => x.method === 'session.resume').map((x) => x.params.session_id);
  assert.deepEqual(resumes, ['s-groceries', 's-repo2']);
});

test('the default destination and messaging read-only setting apply', async () => {
  const f = fixture();
  await f.signIn();
  await f.service.config({ type: 'destination', destination: 's-repo' });
  assert.equal((await f.service.action({ action: 'send_to_agent', args: { message: 'x' } })).text, 'Sent to "Repo cleanup".');
  await f.service.config({ type: 'messagingReadOnly', value: true });
  await assert.rejects(f.service.action({ action: 'send_to_agent', args: { message: 'x', chat_id: 'Groceries' } }), /telegram chat/);
});

test('a queued prompt says so', async () => {
  const f = fixture({ gateway: { 'prompt.submit': () => ({ status: 'queued' }) } });
  await f.signIn();
  assert.match((await f.service.action({ action: 'send_to_agent', args: { message: 'x', chat_id: 'Groceries' } })).text, /^Queued for/);
});

test('a drop before submit is "not sent"; after submit it is uncertain and never resent', async () => {
  const before = fixture({ gateway: { 'session.create': () => 'drop' } });
  await before.signIn();
  await assert.rejects(before.service.action({ action: 'send_to_agent', args: { message: 'x' } }),
    (e) => e.code === 'PLUGIN_UNAVAILABLE' && /not sent/.test(e.message));
  const after = fixture({ gateway: { 'prompt.submit': () => 'drop' } });
  await after.signIn();
  await assert.rejects(after.service.action({ action: 'send_to_agent', args: { message: 'x' } }),
    (e) => e.code === 'UNKNOWN' && /before sending again/.test(e.message));
  assert.equal(after.socketLog.filter((x) => x.method === 'prompt.submit').length, 1);
});

test('server requests (approvals) are declined so other clients can answer', async () => {
  const f = fixture({ gateway: { 'prompt.submit': (params, socket) => {
    socket.onmessage({ data: JSON.stringify({ jsonrpc: '2.0', id: 'srq-1', method: 'approval', params: {} }) });
    return { status: 'streaming' };
  } } });
  await f.signIn();
  await f.service.action({ action: 'send_to_agent', args: { message: 'x' } });
  const reply = f.socketLog.find((x) => x.id === 'srq-1');
  assert.equal(reply.error.code, 4404);
});

test('tokens near expiry are refreshed once, under a lease', async () => {
  const f = fixture();
  await f.signIn();
  f.clock.now += 3550 * 1000; // inside the refresh margin
  await f.service.source({ category: 'agent', item: 'chats' });
  assert.equal(f.calls.filter((c) => c.key === 'POST /auth/native/refresh').length, 1);
  assert.equal(f.calls.at(-1).init.headers.Authorization, 'Bearer AT2');
  assert.equal(f.local.has('hermes.refresh.v1'), false);
  // Another run holding the lease: this run keeps its token instead of replaying the refresh.
  f.clock.now += 7200 * 1000;
  f.local.set('hermes.refresh.v1', JSON.stringify({ at: f.clock.now, id: 'other' }));
  await f.service.source({ category: 'agent', item: 'configured' });
  await f.service.source({ category: 'agent', item: 'chats' });
  assert.equal(f.calls.filter((c) => c.key === 'POST /auth/native/refresh').length, 1);
});

test('a rejected refresh asks for sign-in again', async () => {
  const f = fixture({ routes: { 'POST /auth/native/refresh': () => json({ detail: 'session_expired' }, 401) } });
  await f.signIn();
  f.clock.now += 3550 * 1000;
  await assert.rejects(f.service.source({ category: 'agent', item: 'chats' }), (e) => e.code === 'AUTH_REQUIRED');
  const configured = await f.service.source({ category: 'agent', item: 'configured' });
  assert.equal(configured.instances[0].properties.configured.boolean.value, false);
});

test('sources: configured, chats and status', async () => {
  const f = fixture();
  let configured = await f.service.source({ category: 'agent', item: 'configured' });
  assert.equal(configured.instances[0].properties.configured.boolean.value, false);
  await f.signIn();
  configured = await f.service.source({ category: 'agent', item: 'configured' });
  assert.equal(configured.instances[0].properties.configured.boolean.value, true);
  const chats = await f.service.source({ category: 'agent', item: 'chats' });
  assert.deepEqual(chats.instances.map((i) => i.instanceId), ['s-groceries', 's-repo', 's-repo2']);
  assert.equal(chats.instances[0].properties.preview.longText.text, 'Milk');
  assert.equal(chats.instances[0].properties.active.boolean.value, true);
  const status = await f.service.source({ category: 'hermes', item: 'status' });
  assert.equal(status.instances[0].properties.active_chats.numericValue.value, 1);
  assert.equal(status.instances[0].properties.latest_chat.longText.text, 'Groceries');
});

test('read_latest_reply returns the newest assistant text without MEDIA tags', async () => {
  const f = fixture({ routes: { 'GET /api/sessions/s-repo/messages': () => json({ messages: [
    { id: 9, role: 'assistant', content: 'Done. MEDIA:/home/h/.hermes/cache/audio/r.ogg' },
    { id: 8, role: 'user', content: 'tidy it' },
    { id: 7, role: 'assistant', content: 'older' },
  ] }) } });
  await f.signIn();
  const result = await f.service.action({ action: 'read_latest_reply', args: { chat_id: 'Repo cleanup' } });
  assert.equal(result.text, 'Done.');
});

test('signing out removes credentials', async () => {
  const f = fixture();
  await f.signIn();
  await f.service.config({ type: 'disconnect' });
  assert.equal(f.secure.size, 0);
  await assert.rejects(f.service.action({ action: 'send_to_agent', args: { message: 'x' } }), (e) => e.code === 'AUTH_REQUIRED');
});
