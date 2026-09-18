export const STOP_SEQUENCES = ['\nUSER:', '\nASSISTANT:'];

const TEMPLATE_LINE = /^(?:system|user|assistant|developer|tool)\s*:/i;
const DIAGNOSTIC_LINE = /^(?:provider\s*(?:·|:)|http\s+(?:4\d\d|5\d\d)|ai_providers_unavailable|modo\s+local\s+activo|respaldo\s+local\s+activado)\b/i;

export function sanitizeAssistantText(raw) {
  let text = String(raw || '')
    .replace(/\r\n?/g, '\n')
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, '');

  const lower = text.toLowerCase();
  const stops = STOP_SEQUENCES
    .map((stop) => ({ stop, index: lower.indexOf(stop.toLowerCase()) }))
    .filter((item) => item.index >= 0)
    .sort((a, b) => a.index - b.index);
  if (stops.length) text = text.slice(0, stops[0].index);

  const lines = text
    .split('\n')
    .map((line) => line.trimEnd())
    .filter((line) => {
      const normalized = line.trim();
      return !TEMPLATE_LINE.test(normalized) && !DIAGNOSTIC_LINE.test(normalized);
    });

  return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim().slice(0, 12000);
}

export function classifyIntent(messages = []) {
  const userText = [...messages]
    .reverse()
    .find((message) => message?.role === 'user')
    ?.content
    ?.toLowerCase()
    ?.trim() || '';

  if (/\b(gps|ubicaci[oó]n|coordenadas|mapa)\b/.test(userText)) return 'LOCATION';
  if (/\b(sismo|sismos|terremoto|magnitud|epicentro|tsunami|réplica|replica)\b/.test(userText)) return 'SEISMIC';
  if (/\b(multimedia|video|vídeo|foto|imagen|editar|recortar)\b/.test(userText)) return 'MULTIMEDIA';
  if (/\b(red|internet|conectividad|offline|online|servidor|render|backend)\b/.test(userText)) return 'CONNECTIVITY';
  if (/\b(config|configuración|ajustes|preferencias)\b/.test(userText)) return 'CONFIG';
  if (/\b(estado|status|funciona|funcionando|diagnóstico|diagnostico)\b/.test(userText)) return 'STATUS';
  if (/^(hola|holi|buenas|hey|hello)\b/.test(userText)) return 'GREETING';
  return 'GENERAL';
}

export const LOCAL_FIRST_INTENTS = new Set([
  'LOCATION',
  'SEISMIC',
  'MULTIMEDIA',
  'CONNECTIVITY',
  'CONFIG',
  'STATUS',
  'GREETING'
]);
