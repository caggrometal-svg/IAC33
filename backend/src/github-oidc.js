import crypto from 'node:crypto';

const ISSUER = 'https://token.actions.githubusercontent.com';
const JWKS_URL = 'https://token.actions.githubusercontent.com/.well-known/jwks';
const DEFAULT_AUDIENCE = 'iac33-backend';
const DEFAULT_REPOSITORY = 'caggrometal-svg/IAC33';
const DEFAULT_REPOSITORY_ID = '1372305375';
const DEFAULT_REPOSITORY_OWNER = 'caggrometal-svg';
const DEFAULT_REPOSITORY_OWNER_ID = '322356974';
const DEFAULT_SUBJECT = 'repo:caggrometal-svg/IAC33:ref:refs/heads/main';
const DEFAULT_WORKFLOW_REF = 'caggrometal-svg/IAC33/.github/workflows/ota-release.yml@refs/heads/main';
const DEFAULT_WORKFLOW = 'IAC33 OTA Release';
const CLOCK_SKEW_SECONDS = 60;
const JWKS_TTL_MS = 10 * 60 * 1000;
let jwksCache = { expiresAt: 0, keys: [] };

function decodePart(value) {
  return Buffer.from(value, 'base64url').toString('utf8');
}

function constantTimeJsonValue(value, expected) {
  if (typeof value !== 'string' || typeof expected !== 'string') return false;
  const a = Buffer.from(value);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

async function fetchJwks(force = false) {
  if (!force && Date.now() < jwksCache.expiresAt && jwksCache.keys.length) return jwksCache.keys;
  const response = await fetch(JWKS_URL, { signal: AbortSignal.timeout(5000), headers: { accept: 'application/json' } });
  if (!response.ok) throw new Error('GITHUB_OIDC_JWKS_UNAVAILABLE');
  const body = await response.json();
  if (!body || !Array.isArray(body.keys) || !body.keys.length) throw new Error('GITHUB_OIDC_JWKS_INVALID');
  jwksCache = { expiresAt: Date.now() + JWKS_TTL_MS, keys: body.keys };
  return jwksCache.keys;
}

function validateClaims(claims, {
  audience = process.env.GITHUB_OIDC_AUDIENCE || DEFAULT_AUDIENCE,
  repository = DEFAULT_REPOSITORY,
  workflowRef = DEFAULT_WORKFLOW_REF,
  workflow = DEFAULT_WORKFLOW,
  repositoryId = process.env.GITHUB_OIDC_REPOSITORY_ID || DEFAULT_REPOSITORY_ID,
  repositoryOwner = DEFAULT_REPOSITORY_OWNER,
  repositoryOwnerId = DEFAULT_REPOSITORY_OWNER_ID,
  subject = process.env.GITHUB_OIDC_SUBJECT || DEFAULT_SUBJECT,
} = {}) {
  const now = Math.floor(Date.now() / 1000);
  if (!constantTimeJsonValue(claims.iss, ISSUER)) return false;
  if (!constantTimeJsonValue(claims.aud, audience)) return false;
  if (!constantTimeJsonValue(claims.repository, repository)) return false;
  if (!constantTimeJsonValue(claims.repository_id, repositoryId)) return false;
  if (!constantTimeJsonValue(claims.repository_owner, repositoryOwner)) return false;
  if (!constantTimeJsonValue(claims.repository_owner_id, repositoryOwnerId)) return false;
  if (!constantTimeJsonValue(claims.ref, 'refs/heads/main')) return false;
  if (!constantTimeJsonValue(claims.ref_type, 'branch')) return false;
  if (!constantTimeJsonValue(claims.sub, subject)) return false;
  if (claims.event_name !== 'push' && claims.event_name !== 'workflow_dispatch') return false;
  if (!constantTimeJsonValue(claims.workflow_ref, workflowRef)) return false;
  if (!constantTimeJsonValue(claims.workflow, workflow)) return false;
  const exp = Number(claims.exp);
  const nbf = claims.nbf == null ? null : Number(claims.nbf);
  const iat = claims.iat == null ? null : Number(claims.iat);
  if (!Number.isFinite(exp) || exp < now - CLOCK_SKEW_SECONDS) return false;
  if (nbf != null && (!Number.isFinite(nbf) || nbf > now + CLOCK_SKEW_SECONDS)) return false;
  if (iat != null && (!Number.isFinite(iat) || iat > now + CLOCK_SKEW_SECONDS || iat < now - 15 * 60)) return false;
  return true;
}

export async function verifyGitHubActionsToken(token, options = {}) {
  if (typeof token !== 'string' || token.split('.').length !== 3) return false;
  let header;
  let claims;
  let signingInput;
  let signature;
  try {
    const [encodedHeader, encodedClaims, encodedSignature] = token.split('.');
    header = JSON.parse(decodePart(encodedHeader));
    claims = JSON.parse(decodePart(encodedClaims));
    signingInput = Buffer.from(encodedHeader + '.' + encodedClaims);
    signature = Buffer.from(encodedSignature, 'base64url');
  } catch {
    return false;
  }
  if (header.alg !== 'RS256' || typeof header.kid !== 'string') return false;
  if (!validateClaims(claims, options)) return false;
  let keys;
  try {
    keys = await fetchJwks(false);
  } catch {
    return false;
  }
  const candidate = keys.find((key) => key && key.kid === header.kid && key.kty === 'RSA');
  if (!candidate) return false;
  try {
    const publicKey = crypto.createPublicKey({ key: candidate, format: 'jwk' });
    if (crypto.verify('RSA-SHA256', signingInput, publicKey, signature)) return true;
  } catch {
    return false;
  }
  try {
    keys = await fetchJwks(true);
    const refreshed = keys.find((key) => key && key.kid === header.kid && key.kty === 'RSA');
    if (!refreshed) return false;
    const publicKey = crypto.createPublicKey({ key: refreshed, format: 'jwk' });
    return crypto.verify('RSA-SHA256', signingInput, publicKey, signature);
  } catch {
    return false;
  }
}

export function resetGitHubOidcCache() {
  jwksCache = { expiresAt: 0, keys: [] };
}
