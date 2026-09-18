const CSN_URL = 'https://www.sismologia.cl/';
const CACHE_TTL_MS = 60_000;
const MAX_EVENTS = 50;

let cache = { fetchedAt: 0, events: [] };

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

export function parseCsnLatest(html) {
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
      id: `csn-${occurredAtLocal}-${place}-${magnitude}`.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''),
      source: 'CSN',
      occurredAtLocal,
      place,
      depthKm,
      magnitude
    });
  }
  return events;
}

export async function fetchLatestSeismic({ fetchImpl = fetch, now = Date.now() } = {}) {
  if (cache.events.length && now - cache.fetchedAt < CACHE_TTL_MS) {
    return { source: 'CSN', cached: true, fetchedAt: cache.fetchedAt, events: cache.events };
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8_000);
  try {
    const response = await fetchImpl(CSN_URL, {
      headers: { accept: 'text/html,application/xhtml+xml', 'user-agent': 'IAC33/1.0 seismic-client' },
      signal: controller.signal
    });
    if (!response.ok) throw new Error(`CSN_HTTP_${response.status}`);
    const events = parseCsnLatest(await response.text());
    if (!events.length) throw new Error('CSN_EMPTY_FEED');
    cache = { fetchedAt: now, events };
    return { source: 'CSN', cached: false, fetchedAt: now, events };
  } finally {
    clearTimeout(timer);
  }
}

export function clearSeismicCache() {
  cache = { fetchedAt: 0, events: [] };
}
