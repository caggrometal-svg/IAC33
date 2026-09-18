import test from 'node:test';
import assert from 'node:assert/strict';
import { clearSeismicCache, fetchLatestSeismic, parseCsnLatest } from '../src/seismic.js';

test('parseCsnLatest normalizes CSN latest earthquake rows', () => {
  const html = `
    <h2>Últimos sismos</h2>
    <div>2026-09-17 12:51:16</div>
    <div>29 km al SE de Bahía Mansa</div>
    <div>41 km</div>
    <div>4.5</div>
    <div>2026-09-17 13:17:06</div>
    <div>22 km al NE de Los Andes</div>
    <div>15 km</div>
    <div>2.5</div>
  `;
  const events = parseCsnLatest(html);
  assert.equal(events.length, 2);
  assert.equal(events[0].source, 'CSN');
  assert.equal(events[0].place, '29 km al SE de Bahía Mansa');
  assert.equal(events[0].depthKm, 41);
  assert.equal(events[0].magnitude, 4.5);
});

test('fetchLatestSeismic caches successful CSN reads', async () => {
  clearSeismicCache();
  let calls = 0;
  const html = `
    <h2>Últimos sismos</h2>
    <div>2026-09-17 12:51:16</div>
    <div>29 km al SE de Bahía Mansa</div>
    <div>41 km</div>
    <div>4.5</div>
  `;
  const fetchImpl = async () => {
    calls += 1;
    return { ok: true, text: async () => html };
  };
  const first = await fetchLatestSeismic({ fetchImpl, now: 1000 });
  const second = await fetchLatestSeismic({ fetchImpl, now: 2000 });
  assert.equal(first.cached, false);
  assert.equal(second.cached, true);
  assert.equal(calls, 1);
});
