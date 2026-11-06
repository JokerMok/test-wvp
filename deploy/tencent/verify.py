#!/usr/bin/env python3
import argparse
import base64
import hashlib
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
parser.add_argument('base')
parser.add_argument('sha')
parser.add_argument('--edge', action='store_true')
args = parser.parse_args()
credentials = dict(line.split('=', 1) for line in
                   Path('/srv/apps/test-wvp/shared/config/access.env').read_text().splitlines())
headers = {}
if args.edge:
    encoded = base64.b64encode(f'{credentials["EDGE_USERNAME"]}:{credentials["EDGE_PASSWORD"]}'.encode())
    headers['Authorization'] = 'Basic ' + encoded.decode()

def request(path, data=None, extra=None):
    req = Request(args.base.rstrip('/') + path, data=data, headers={**headers, **(extra or {})})
    try:
        with urlopen(req, timeout=8) as response:
            return response.status, response.headers, response.read()
    except HTTPError as error:
        return error.code, error.headers, error.read()

def api(path, data=None, extra=None):
    status, response_headers, body = request(path, data, extra)
    assert status == 200, (path, status)
    result = json.loads(body)
    assert result['code'] == 0, (path, result['code'])
    return response_headers, result['data']

status, _, body = request('/api/server/version')
assert status in (401, 403) or (status == 200 and json.loads(body)['code'] == 401), 'Anonymous API access'
login = urlencode({
    'username': credentials['WVP_USERNAME'],
    'password': hashlib.md5(credentials['WVP_PASSWORD'].encode()).hexdigest(),
}).encode()
response, login_user = api('/api/user/login', login)
token = response.get('access-token') or login_user['accessToken']
authorized = {'access-token': token}
_, version = api('/api/server/version', extra=authorized)
assert version.get('git_Revision', version.get('GIT_Revision')) == args.sha, 'Unexpected running Git revision'
_, devices = api('/api/device/query/devices?page=1&count=10', extra=authorized)
assert 'total' in devices and isinstance(devices['list'], list), 'GB28181 device query failed'
_, nodes = api('/api/server/media_server/online/list', extra=authorized)
assert any(node['id'] == 'test-wvp-zlm' for node in nodes), 'Media server is not online'
print(f"WVP {version['version']} {args.sha}; login, GB28181 device list ({devices['total']}), media node OK")
