const allowedTransitions = new Map([
  ['PENDING', new Set(['CLAIMED', 'EXPIRED', 'REJECTED'])],
  ['CLAIMED', new Set(['EXECUTING', 'FAILED', 'EXPIRED'])],
  ['EXECUTING', new Set(['SUCCEEDED', 'FAILED'])]
]);

function validCommand(input) {
  return Boolean(input) &&
    typeof input.id === 'string' && input.id.length > 0 && input.id.length <= 128 &&
    typeof input.type === 'string' && /^[A-Za-z0-9._:-]{1,64}$/.test(input.type) &&
    Boolean(input.payload) && typeof input.payload === 'object' &&
    typeof input.idempotencyKey === 'string' && /^[A-Za-z0-9._:-]{8,128}$/.test(input.idempotencyKey) &&
    Number.isFinite(Date.parse(input.expiresAt));
}

export { allowedTransitions, validCommand };
