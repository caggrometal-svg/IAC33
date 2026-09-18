import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildForecast,
  clearSeismicCache,
  fetchLatestSeismic,
  parseCsnLatest,
  parseUsgsGeoJson
} from '../src/seismic.js';

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
  assert.equal(events[0].latitude, null);
  assert.equal(events[0].longitude, null);
});

test('parseUsgsGeoJson extracts coordinates for mapped events', () => {
  const events = parseUsgsGeoJson({
    features: [{
      id: 'abc',
      properties: { mag: 5.2, place: 'Chile', time: 1000 },
      geometry: { coordinates: [-72.1, -38.7, 20.0] }
    }]
  });
  assert.equal(events.length, 1);
  assert.equal(events[0].latitude, -38.7);
  assert.equal(events[0].longitude, -72.1);
  assert.equal(events[0].depthKm, 20);
});

test('buildForecast returns Poisson probabilities from historical rate', () => {
  const events = Array.from({ length: 100 }, (_, index) => ({
    id: String(index),
    magnitude: 4.5,
    depthKm: 10,
    latitude: -38,
    longitude: -72,
    source: 'USGS',
    timestampMs: index
  }));
  const forecast = buildForecast(events, 10, 4.0);
  assert.equal(forecast.sampleCount, 100);
  assert.ok(forecast.bValue > 0);
  assert.ok(forecast.estimates[0].probability7d > 0);
  assert.ok(forecast.estimates[0].probability7d < 1);
});

test('fetchLatestSeismic caches successful CSN reads without requiring USGS in test doubles', async () => {
  clearSeismicCache();
  let calls = 0;
  const html = `
    <h2>Últimos sismos</h2>
    <div>2026-09-17 12:51:16</div>
    <div>29 km al SE de Bahía Mansa</div>
    <div>41 km</div>
    <div>4.5</div>
  `;
  const fetchImpl = async (url) => {
    calls += 1;
    if (String(url).startsWith('https://www.sismologia.cl/')) {
      return { ok: true, text: async () => html };
    }
    return { ok: false, status: 503, json: async () => ({}) };
  };
  const first = await fetchLatestSeismic({ fetchImpl, now: 1_000 });
  const second = await fetchLatestSeismic({ fetchImpl, now: 2_000 });
  assert.equal(first.cached, false);
  assert.equal(second.cached, true);
  assert.equal(calls, 3);
});
