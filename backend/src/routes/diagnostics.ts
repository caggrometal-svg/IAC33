import { providerHealth } from '../ai/provider-health.ts';
import { diagnoseConnectivity } from '../ai-router.js';

export async function buildAiDiagnostics(order = []) {
  const live = await diagnoseConnectivity();
  return {
    ok: live.internet.state === 'OK',
    service: 'iac33-ai',
    router: live.router,
    timestamp: live.generatedAt,
    providers: live.providers,
    internet: live.internet,
    configuredOrder: order,
    runtimeHealth: providerHealth.snapshot(order)
  };
}
