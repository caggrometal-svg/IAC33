import { providerHealth } from '../ai/provider-health.ts';

export function buildAiDiagnostics(order = []) {
  const providers = providerHealth.snapshot(order);

  return {
    ok: true,
    service: 'iac33-ai',
    router: 'sequential-cascade',
    timestamp: new Date().toISOString(),
    providers
  };
}
