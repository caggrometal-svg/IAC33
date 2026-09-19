import test from 'node:test';
import assert from 'node:assert/strict';

const router = await import('../src/ai-router.js?connectivity-diagnostics-test=' + Date.now());

test('diagnostico_conectividad se reconoce y formatea estados explícitos', () => {
  assert.equal(
    router.isConnectivityDiagnosticRequest([
      { role: 'user', content: 'Ejecuta un diagnóstico completo de conectividad' }
    ]),
    true
  );

  const text = router.formatConnectivityDiagnostics({
    internet: { state: 'OK', status: 204, latencyMs: 12 },
    backendRouter: { state: 'OK' },
    androidToBackend: { state: 'NOT_VERIFIED' },
    providers: [
      { provider: 'animica', state: 'OK', status: 200, latencyMs: 4 },
      { provider: 'groq', state: 'NOT_CONFIGURED' }
    ],
    circuits: [
      { provider: 'animica', open: false },
      { provider: 'groq', open: false }
    ],
    order: ['animica', 'groq']
  });

  assert.match(text, /INTERNET: OK/);
  assert.match(text, /ANIMICA: OK · HTTP 200/);
  assert.match(text, /GROQ: NOT_CONFIGURED/);
  assert.match(text, /RUTA DISPONIBLE: animica/);
  assert.match(text, /ARCHIVO\/MÓDULO: backend\/src\/ai-router\.js/);
});
