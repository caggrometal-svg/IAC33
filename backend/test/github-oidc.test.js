import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { resetGitHubOidcCache, verifyGitHubActionsToken } from '../src/github-oidc.js';

const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicJwk = publicKey.export({ format: 'jwk' });
publicJwk.kid = 'test-kid';

function token(overrides = {}) {
  const header = Buffer.from(JSON.stringify({ alg: 'RS256', kid: 'test-kid', typ: 'JWT' })).toString('base64url');
  const claims = Buffer.from(JSON.stringify({
    iss: 'https://token.actions.githubusercontent.com',
    aud: 'iac33-backend',
    repository: 'caggrometal-svg/IAC33',
    ref: 'refs/heads/main',
    workflow_ref: 'caggrometal-svg/IAC33/.github/workflows/ota-release.yml@refs/heads/main',
    workflow: 'IAC33 OTA Release',
    iat: Math.floor(Date.now() / 1000),
    nbf: Math.floor(Date.now() / 1000) - 5,
    exp: Math.floor(Date.now() / 1000) + 300,
    ...overrides,
  })).toString('base64url');
  const signingInput = Buffer.from(header + '.' + claims);
  const signature = crypto.sign('RSA-SHA256', signingInput, privateKey).toString('base64url');
  return header + '.' + claims + '.' + signature;
}

const originalFetch = global.fetch;
test.beforeEach(() => {
  resetGitHubOidcCache();
  global.fetch = async () => new Response(JSON.stringify({ keys: [publicJwk] }), {
    status: 200,
    headers: { 'content-type': 'application/json' },
  });
});
test.after(() => { global.fetch = originalFetch; });

test('accepts a valid IAC33 GitHub Actions OIDC token', async () => {
  assert.equal(await verifyGitHubActionsToken(token()), true);
});

test('rejects a token from another repository', async () => {
  assert.equal(await verifyGitHubActionsToken(token({ repository: 'other/repo' })), false);
});

test('rejects a token from another branch', async () => {
  assert.equal(await verifyGitHubActionsToken(token({ ref: 'refs/heads/dev' })), false);
});

test('rejects a token from another workflow', async () => {
  assert.equal(await verifyGitHubActionsToken(token({ workflow: 'Other Workflow' })), false);
});
