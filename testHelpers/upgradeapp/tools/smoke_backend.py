"""Live header-session smoke; credentials stay in memory, only scoped owners are cleaned."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', required=True)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output must be new')
    args.output.mkdir(parents=True, mode=0o700)
    run = uuid.uuid4().hex
    (args.output / 'request.json').write_text(json.dumps(dict(requestId=run, email=run + '@upgrade.example')))
    events = []
    owner = None
    stage = 'capabilities'

    def require(condition, label):
        if not condition:
            raise RuntimeError(label)

    def request(path, body=None, token=None, expected=200):
        headers = {'Content-Type': 'application/json', 'st-auth-mode': 'header', 'rid': 'session'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(args.backend.rstrip('/') + path,
                                     data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            events.append(dict(path=path, status=response.status, expected=expected))
            require(response.status == expected, 'Unexpected HTTP status at ' + path)
            parsed = json.loads(data) if data and 'application/json' in response.headers.get('Content-Type', '') else {}
            return response.headers, parsed

    try:
        _, cap = request('/test/capabilities')
        require(cap['mappedGoogleSession'] and cap['compatible'], 'Fixture capability unavailable')
        stage = 'create'
        headers, owner = request('/test/email-change-owner', dict(recipe='google', email=run + '@upgrade.example'))
        (args.output / 'owner.json').write_text(json.dumps(dict(fixtureId=owner['fixtureId'], handle=owner['handle'])))
        access, refresh = headers.get('st-access-token'), headers.get('st-refresh-token')
        require(bool(access) and bool(refresh), 'Creation did not return real credentials')
        require(owner['google'] and owner['refreshToken'] == refresh, 'Creation contract mismatch')
        identity = dict(fixtureId=owner['fixtureId'], handle=owner['handle'])
        stage = 'active-state'
        _, state = request('/test/owner-session-state', identity)
        require(state == dict(owned=True, active=True), 'Created session not active in Core')
        request('/test/owner-session-state', dict(fixtureId=owner['fixtureId'], handle='wrong-handle'), expected=404)
        stage = 'protected'
        headers, protected = request('/test/protected', token=access)
        require(protected['userId'] == owner['userId'] and protected['handle'] == owner['handle'], 'Protected identity mismatch')
        access = headers.get('st-access-token') or access
        stage = 'refresh'
        headers, _ = request('/auth/session/refresh', {}, refresh)
        replacement = headers.get('st-refresh-token')
        require(bool(replacement) and replacement != refresh, 'Refresh credential did not rotate')
        refresh = replacement
        access = headers.get('st-access-token')
        require(bool(access), 'Refresh access missing')
        headers, protected = request('/test/protected', token=access)
        require(protected['userId'] == owner['userId'] and protected['handle'] == owner['handle'], 'Refreshed identity mismatch')
        access = headers.get('st-access-token') or access
        stage = 'logout-revocation'
        request('/auth/signout', {}, access)
        _, state = request('/test/owner-session-state', identity)
        require(state == dict(owned=True, active=False), 'Logout did not revoke Core session')
        request('/auth/session/refresh', {}, refresh, expected=401)
        stage = 'cleanup-retry'
        for _ in range(2):
            _, cleanup = request('/test/cleanup-owner', dict(fixtureId=owner['fixtureId']))
            require(cleanup.get('cleaned') is True, 'Known-owner cleanup/retry failed')
        _, unknown = request('/test/cleanup-owner', dict(fixtureId='unowned-' + run))
        require(unknown.get('cleaned') is False, 'Unknown owner falsely acknowledged')
        stage = 'passed'
    except Exception:
        # Third-party exception strings/response bodies may include credentials.
        raise SystemExit('Backend smoke failed at ' + stage + '; inspect private evidence and original fixture ownership')
    finally:
        if owner is not None:
            try:
                _, cleanup = request('/test/cleanup-owner', dict(fixtureId=owner['fixtureId']))
                require(cleanup.get('cleaned') is True, 'Final scoped cleanup failed')
            except Exception:
                stage = 'cleanup-failed'
        (args.output / 'results.json').write_text(json.dumps(dict(stage=stage, requests=events), indent=2))
        if stage == 'cleanup-failed':
            raise SystemExit('Scoped cleanup failed; retain original backend process and owner.json')
    print('PASS: create, active-state, protected, refresh, logout, revoked-state, rejected-refresh, cleanup retry, unknown-owner rejection')


if __name__ == '__main__':
    main()
