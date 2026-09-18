const CSN_URL = 'https://www.sismologia.cl/';
const USGS_URL = 'https://earthquake.usgs.gov/fdsnws/event/1/query';
const CACHE_TTL_MS = 60_000;
const HISTORY_CACHE_TTL_MS = 30 * 60_000;
const MAX_EVENTS = 50;
const MAX_MAP_EVENTS = 120;
const HISTORY_YEARS = 10;
const COMPLETENESS_MAGNITUDE = 4.0;

let cache = { fetchedAt: 0, events: [], mapEvents: [], forecast: null };
let historyCache = { fetchedAt: 0, events: [] };

function htmlToText(html) {
  return String(html)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&#39;/g, "'")
    .replace(/&quot;/gi, '"')
    .replace(/\s+/g, ' ')
    .trim();
}

function numberOrNull(value) {
  const n = Number(String(value).replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

function parseCsnLatest(html) {
  const text = htmlToText(html);
  const marker = text.indexOf('Últimos sismos');
  if (marker < 0) return [];
  const section = text.slice(marker, marker + 30_000);
  const pattern = /(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s+(.+?)\s+(\d+)\s*km\s+([0-9]+(?:[.,][0-9]+)?)/g;
  const events = [];
  let match;
  while ((match = pattern.exec(section)) && events.length < MAX_EVENTS) {
    const occurredAtLocal = match[1];
    const place = match[2].replace(/\s+/g, ' ').trim();
    const depthKm = numberOrNull(match[3]);
    const magnitude = numberOrNull(match[4]);
    if (!place || depthKm === null || magnitude === null) continue;
    events.push({
      id: 'csn-' + occurredAtLocal + '-' + place + '-' + magnitude,
      source: 'CSN',
      occurredAtLocal,
      place,
      depthKm,
      magnitude,
      latitude: null,
      longitude: null
    });
  }
  return events;
}

function parseUsgsGeoJson(json) {
  const features = Array.isArray(json?.features) ? json.features : [];
  return features.map((feature) => {
    const coordinates = Array.isArray(feature?.geometry?.coordinates) ? feature.geometry.coordinates : [];
    const props = feature?.properties || {};
    const magnitude = numberOrNull(props.mag);
    const longitude = numberOrNull(coordinates[0]);
    const latitude = numberOrNull(coordinates[1]);
    const depthKm = numberOrNull(coordinates[2]);
    const timestampMs = Number(props.time);
    return {
      id: String(feature?.id || props.code || ('usgs-' + timestampMs)),
      source: 'USGS',
      occurredAtLocal: Number.isFinite(timestampMs) ? new Date(timestampMs).toISOString() : '',
      place: String(props.place || 'Ubicación desconocida'),
      depthKm: depthKm === null ? 0 : depthKm,
      magnitude: magnitude === null ? 0 : magnitude,
      latitude,
      longitude,
      timestampMs: Number.isFinite(timestampMs) ? timestampMs : 0
    };
  }).filter((event) => event.latitude !== null && event.longitude !== null && event.magnitude > 0);
}

function buildUsgsUrl({ startMs, endMs, minMagnitude, limit }) {
  const params = new URLSearchParams({
    format: 'geojson',
    starttime: new Date(startMs).toISOString(),
    endtime: new Date(endMs).toISOString(),
    minlatitude: '-56',
    maxlatitude: '-17',
    minlongitude: '-76',
    maxlongitude: '-66',
    minmagnitude: String(minMagnitude),
    orderby: 'time',
    limit: String(limit)
  });
  return USGS_URL + '?' + params.toString();
}

async function fetchUsgs({ startMs, endMs, minMagnitude, limit, fetchImpl = fetch }) {
  const response = await fetchImpl(buildUsgsUrl({ startMs, endMs, minMagnitude, limit }), {
    headers: { accept: 'application/geo+json,application/json', 'user-agent': 'IAC33/1.0 seismic-analysis' },
    signal: AbortSignal.timeout(12_000)
  });
  if (!response.ok) throw new Error('USGS_HTTP_' + response.status);
  return parseUsgsGeoJson(await response.json());
}

function chileLocalToMs(value) {
  const match = String(value).match(/^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2})$/);
  if (!match) return NaN;
  return Date.parse(match[1] + 'T' + match[2] + '-03:00');
}

function enrichCsnEvents(csnEvents, usgsEvents) {
  return csnEvents.map((event) => {
    const eventTime = chileLocalToMs(event.occurredAtLocal);
    let best = null;
    let bestScore = Number.POSITIVE_INFINITY;
    for (const candidate of usgsEvents) {
      const timeDiff = Number.isFinite(eventTime) && candidate.timestampMs
        ? Math.abs(candidate.timestampMs - eventTime)
        : Number.POSITIVE_INFINITY;
      const magDiff = Math.abs(candidate.magnitude - event.magnitude);
      if (timeDiff > 20 * 60 * 1000 || magDiff > 0.30) continue;
      const score = timeDiff / 1_000_000 + magDiff * 60;
      if (score < bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best ? { ...event, latitude: best.latitude, longitude: best.longitude } : event;
  });
}

function buildForecast(historyEvents, years = HISTORY_YEARS, minimumMagnitude = COMPLETENESS_MAGNITUDE) {
  const sample = historyEvents.filter((event) => event.magnitude >= minimumMagnitude);
  if (!sample.length) {
    return {
      method: 'Gutenberg-Richter + Poisson',
      interpretation: 'Sin muestra suficiente para estimar probabilidades.',
      historyYears: years,
      completenessMagnitude: minimumMagnitude,
      sampleCount: 0,
      bValue: null,
      estimates: []
    };
  }

  const meanMagnitude = sample.reduce((sum, event) => sum + event.magnitude, 0) / sample.length;
  const denominator = meanMagnitude - (minimumMagnitude - 0.05);
  const bValue = denominator > 0 ? Math.log10(Math.E) / denominator : null;
  const annualRateAtCompleteness = sample.length / Math.max(years, 0.1);

  const estimates = [minimumMagnitude, 5.0, 6.0].map((threshold) => {
    const rate = bValue && Number.isFinite(bValue)
      ? annualRateAtCompleteness * Math.pow(10, -bValue * (threshold - minimumMagnitude))
      : 0;
    const expected7d = rate * 7 / 365.25;
    const expected30d = rate * 30 / 365.25;
    return {
      magnitudeThreshold: threshold,
      annualRate: rate,
      expected7d,
      probability7d: 1 - Math.exp(-expected7d),
      expected30d,
      probability30d: 1 - Math.exp(-expected30d)
    };
  });

  return {
    method: 'Gutenberg-Richter + Poisson',
    interpretation: 'Estimación estadística basada en actividad histórica dentro de una caja geográfica aproximada de Chile.',
    historyYears: years,
    completenessMagnitude: minimumMagnitude,
    sampleCount: sample.length,
    bValue,
    estimates
  };
}

export async function fetchLatestSeismic({ fetchImpl = fetch, now = Date.now() } = {}) {
  if (cache.events.length && now - cache.fetchedAt < CACHE_TTL_MS) {
    return {
      source: 'CSN',
      cached: true,
      fetchedAt: cache.fetchedAt,
      events: cache.events,
      mapEvents: cache.mapEvents,
      forecast: cache.forecast
    };
  }

  const csnResponse = await fetchImpl(CSN_URL, {
    headers: { accept: 'text/html,application/xhtml+xml', 'user-agent': 'IAC33/1.0 seismic-client' },
    signal: AbortSignal.timeout(8_000)
  });
  if (!csnResponse.ok) throw new Error('CSN_HTTP_' + csnResponse.status);
  const csnEvents = parseCsnLatest(await csnResponse.text());
  if (!csnEvents.length) throw new Error('CSN_EMPTY_FEED');

  let recentMap = [];
  try {
    recentMap = await fetchUsgs({
      startMs: now - 30 * 24 * 60 * 60 * 1000,
      endMs: now + 60_000,
      minMagnitude: 3.0,
      limit: 2000,
      fetchImpl
    });
  } catch {
    recentMap = [];
  }

  const events = enrichCsnEvents(csnEvents, recentMap);

  let history = historyCache.events;
  if (!history.length || now - historyCache.fetchedAt >= HISTORY_CACHE_TTL_MS) {
    try {
      history = await fetchUsgs({
        startMs: now - HISTORY_YEARS * 365.25 * 24 * 60 * 60 * 1000,
        endMs: now + 60_000,
        minMagnitude: COMPLETENESS_MAGNITUDE,
        limit: 20000,
        fetchImpl
      });
      historyCache = { fetchedAt: now, events: history };
    } catch {
      history = [];
    }
  }

  const forecast = buildForecast(history, HISTORY_YEARS, COMPLETENESS_MAGNITUDE);
  cache = {
    fetchedAt: now,
    events,
    mapEvents: recentMap.slice(0, MAX_MAP_EVENTS),
    forecast
  };

  return {
    source: 'CSN',
    cached: false,
    fetchedAt: now,
    events,
    mapEvents: cache.mapEvents,
    forecast
  };
}

export function clearSeismicCache() {
  cache = { fetchedAt: 0, events: [], mapEvents: [], forecast: null };
  historyCache = { fetchedAt: 0, events: [] };
}

export { buildForecast, parseCsnLatest, parseUsgsGeoJson };
