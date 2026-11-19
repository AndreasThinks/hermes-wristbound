"""Run the built plugin in pypkjs' plugin host (QuickJS plus the phone app's own host shims: fetch,
WebSocket, secureStorage) against a scripted Hermes dashboard, and check what Hermes received.

    PEBBLE_PY=$(dirname $(readlink -f $(which pebble)))/python   # pebble-tool's interpreter
    NO_PROXY='*' $PEBBLE_PY tools/emulator_e2e.py build/pebble-plugin.pbw

The fake dashboard implements non-redirecting native authorization over HTTPS. The temporary
certificate is trusted by this process only. No watch, emulator or real Hermes is needed.
"""
from gevent import monkey
monkey.patch_all()

import json
import logging
import os
import secrets
import subprocess
from pathlib import Path
import socket
import sys
import tempfile
import zipfile
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit
from uuid import UUID

import gevent
import requests
from gevent import pywsgi
from geventwebsocket.handler import WebSocketHandler

from pypkjs.plugins import manifest as manifests
from pypkjs.plugins.js_plugin import JsPlugin

HOST, PORT = 'hermes.e2e.ts.net', 18943
BASE = 'https://%s:%d' % (HOST, PORT)

# Resolve the tailnet name to this machine; nothing else in the run touches DNS.
_getaddrinfo = socket.getaddrinfo
def _resolve(host, *args, **kwargs):
    return _getaddrinfo('127.0.0.1' if host == HOST else host, *args, **kwargs)
socket.getaddrinfo = _resolve

STATE = {'submits': [], 'logins': [], 'socket_closed': 0, 'bearer': []}
SESSIONS = [
    {'id': 's-groceries', 'title': 'Groceries', 'source': 'telegram', 'is_active': True, 'last_active': 1.8e9},
    {'id': 's-repo', 'title': 'Repo cleanup', 'source': 'cli', 'is_active': False, 'last_active': 1.79e9},
]


def respond(start, status, body, headers=()):
    data = json.dumps(body).encode()
    start(status, [('Content-Type', 'application/json'), ('Content-Length', str(len(data)))] + list(headers))
    return [data]


def app(environ, start):
    path, method = environ['PATH_INFO'], environ['REQUEST_METHOD']
    query = parse_qs(environ.get('QUERY_STRING', ''))
    auth = environ.get('HTTP_AUTHORIZATION', '')
    if path == '/api/ws':
        return gateway(environ, query)
    if path == '/api/status':
        return respond(start, '200 OK', {'auth_required': True})
    if path == '/api/auth/providers':
        return respond(start, '200 OK', {'providers': [{'name': 'basic', 'display_name': 'Password', 'supports_password': True}]})
    if path == '/auth/native/authorize':
        STATE['authorize_state'] = query['state'][0]
        assert query.get('response_mode') == ['json']
        return respond(start, '200 OK', {'native_password': True}, [
            ('Set-Cookie', 'hermes_session_pkce=PKCE; Path=/; Secure; HttpOnly')])
    if path == '/auth/password-login':
        body = json.loads(environ['wsgi.input'].read() or b'{}')
        native = 'hermes_session_pkce=PKCE' in environ.get('HTTP_COOKIE', '')
        STATE['logins'].append({'native': native, 'user': body.get('username')})
        if body.get('password') != 'pw':
            return respond(start, '401 Unauthorized', {'detail': 'Invalid credentials'})
        if not native or body.get('native_only') is not True:
            return respond(start, '400 Bad Request', {'detail': 'Native sign-in unavailable'})
        return respond(start, '200 OK', {'ok': True, 'next': 'http://127.0.0.1/wristbound?code=CODE&state=' + STATE['authorize_state']})
    if path == '/auth/native/token':
        return respond(start, '200 OK', {'access_token': 'AT-NATIVE', 'refresh_token': 'RT-NATIVE', 'expires_at': 4e9, 'provider': 'basic'})
    if not auth.startswith('Bearer AT-'):
        return respond(start, '401 Unauthorized', {'detail': 'Unauthorized'})
    STATE['bearer'].append(auth)
    if path == '/api/sessions':
        return respond(start, '200 OK', {'sessions': SESSIONS})
    if path == '/api/auth/ws-ticket' and method == 'POST':
        return respond(start, '200 OK', {'ticket': 'TICKET', 'ttl_seconds': 30})
    if path.startswith('/api/sessions/') and path.endswith('/messages'):
        return respond(start, '200 OK', {'messages': [{'id': 3, 'role': 'assistant', 'content': 'All tidy. MEDIA:/a.ogg'}]})
    return respond(start, '404 Not Found', {'detail': 'not found'})


def gateway(environ, query):
    ws = environ['wsgi.websocket']
    assert query.get('ticket') == ['TICKET'], query
    ws.send(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'gateway.ready', 'payload': {}}}))
    while True:
        raw = ws.receive()
        if raw is None:
            STATE['socket_closed'] += 1
            return []
        frame = json.loads(raw)
        method, params = frame.get('method'), frame.get('params') or {}
        if method == 'session.create':
            STATE['creates'] = STATE.get('creates', 0) + 1
            k = STATE['creates']
            result = {'session_id': f'rt-s-new-{k}', 'stored_session_id': f's-new-{k}'}
        elif method == 'session.resume':
            result = {'session_id': 'rt-' + params['session_id'], 'running': False}
        elif method == 'prompt.submit':
            STATE['submits'].append(params)
            result = {'status': 'streaming', 'user_row_id': '7'}
        else:
            continue
        ws.send(json.dumps({'jsonrpc': '2.0', 'id': frame['id'], 'result': result}))
        if method == 'prompt.submit':
            ws.send(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'message.start', 'session_id': 'rt-new', 'payload': {}}}))


def main(pbw_path):
    logging.basicConfig(level=logging.WARNING)
    tls = tempfile.TemporaryDirectory()
    cert, key = (str(Path(tls.name) / name) for name in ('cert.pem', 'key.pem'))
    subprocess.run(['openssl', 'req', '-x509', '-nodes', '-newkey', 'rsa:2048', '-days', '1',
                    '-subj', f'/CN={HOST}', '-addext', f'subjectAltName=DNS:{HOST}',
                    '-keyout', key, '-out', cert], check=True, capture_output=True)
    os.environ['WEBSOCKET_CLIENT_CA_BUNDLE'] = cert
    server = pywsgi.WSGIServer(('127.0.0.1', PORT), app, handler_class=WebSocketHandler, log=None,
                               certfile=cert, keyfile=key)
    server.start()
    with zipfile.ZipFile(pbw_path) as z:
        appinfo = json.loads(z.read('appinfo.json'))
        plugin_manifest = manifests.loadable_plugin(appinfo)
        problems = list(manifests.problems(plugin_manifest))
        script = z.read(plugin_manifest['script']).decode()
    uuid = UUID(appinfo['uuid'])
    logs = []
    runner = SimpleNamespace(
        persist_dir=tempfile.mkdtemp(), block_private_addresses=False, running_uuid=None, location=None,
        pbws={uuid: SimpleNamespace(uuid=uuid, manifest=appinfo)}, logger=logging.getLogger('runner'),
        log_output=lambda message: logs.append(message), plugins=None, timeline=None)
    plugin = JsPlugin(uuid, appinfo['shortName'], plugin_manifest, script, runner)

    def config(message):
        plugin.open_config_session(lambda pushed: None)
        try:
            return json.loads(plugin.config_message(json.dumps(message)))
        finally:
            plugin.close_config_session()

    send = requests.Session.send

    def send_with_test_ca(session, request, **kwargs):
        # pypkjs calls Session.send directly with verify=True, bypassing the CA env setting.
        if urlsplit(request.url).netloc == f'{HOST}:{PORT}':
            kwargs['verify'] = cert
        return send(session, request, **kwargs)

    with patch.object(requests.Session, 'send', send_with_test_ca):
        results = {'manifest_problems': problems}
        results['bad_password'] = config({'type': 'connect', 'base': BASE, 'username': 'andreas', 'password': 'nope', 'auth_entropy': list(secrets.token_bytes(56))})
        results['connect'] = config({'type': 'connect', 'base': BASE, 'username': 'andreas', 'password': 'pw', 'auth_entropy': list(secrets.token_bytes(56))})
        results['configured'] = plugin.read('agent', 'configured', None, None)
        results['chats'] = plugin.read('agent', 'chats', None, None)
        results['status'] = plugin.read('hermes', 'status', None, None)
        config({'type': 'destination', 'destination': 'ongoing'})
        results['send_new'] = plugin.invoke('send_to_agent', {'message': 'First capture'})
        results['continue_first'] = plugin.invoke('send_to_agent', {'message': 'First follow-up'})
        results['start_fresh'] = plugin.invoke('send_to_agent', {'message': 'New Hermes session, then Fresh capture'})
        results['send_named'] = plugin.invoke('send_to_agent', {'message': 'Add oat milk', 'chat_id': 'grocer'})
        results['continue_second'] = plugin.invoke('send_to_agent', {'message': 'Second follow-up'})
        config({'type': 'destination', 'destination': 'latest'})
        results['latest_before'] = plugin.invoke('send_to_agent', {'message': 'Latest before desktop change'})
        SESSIONS.insert(0, {'id': 's-desktop', 'title': 'Desktop chat', 'source': 'webui'})
        results['latest_after'] = plugin.invoke('send_to_agent', {'message': 'Latest after desktop change'})
        results['unknown_chat'] = plugin.invoke('send_to_agent', {'message': 'x', 'chat_id': 'zzz'})
        results['read_latest'] = plugin.invoke('read_latest_reply', {'chat_id': 'Repo cleanup'})
        gevent.sleep(0.5)
        results['hermes_saw'] = {'logins': STATE['logins'], 'submits': STATE['submits'],
                                 'sockets_closed_by_plugin': STATE['socket_closed'], 'bearer_tokens': sorted(set(STATE['bearer']))}
        results['plugin_logs'] = logs[-15:]
    print(json.dumps(results, indent=2, default=str))
    server.stop()
    tls.cleanup()
    ok = (results['connect'].get('signedIn') and [p['session_id'] for p in STATE['submits']] ==
          ['rt-s-new-1', 'rt-s-new-1', 'rt-s-new-2', 'rt-s-groceries', 'rt-s-new-2',
           'rt-s-groceries', 'rt-s-desktop']
          and STATE['submits'][2]['text'] == 'Fresh capture' and results['send_new'].get('ok')
          and 'Wrong username or password' in results['bad_password'].get('error', '')
          and len(STATE['logins']) == 2 and all(row['native'] for row in STATE['logins'])
          and results['send_named'].get('ok') and not results['unknown_chat'].get('ok') and not problems)
    sys.exit(0 if ok else 1)


if __name__ == '__main__':
    main(sys.argv[1])
