export const STOP_SEQUENCES = ["\nUSER:", "\nASSISTANT:"] as const;

const TEMPLATE_LINE = /^(?:system|user|assistant|developer|tool)\s*:/i;
const DIAGNOSTIC_LINE = /^(?:provider\s*(?:·|:)|http\s+[45]\d\d|ai_providers_unavailable|modo\s+local\s+activo|respaldo\s+local\s+activado)\b/i;

export function sanitizeAssistantText(raw: unknown): string {
  let text = String(raw ?? "")
    .replace(/\r\n?/g, "\n")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "");

  const lower = text.toLowerCase();
  const stopIndex = STOP_SEQUENCES
    .map((stop) => lower.indexOf(stop.toLowerCase()))
    .filter((index) => index >= 0)
    .sort((a, b) => a - b)[0];

  if (Number.isInteger(stopIndex)) {
    text = text.slice(0, stopIndex);
  }

  return text
    .split("\n")
    .map((line) => line.trimEnd())
    .filter((line) => {
      const normalized = line.trim();
      return !TEMPLATE_LINE.test(normalized) && !DIAGNOSTIC_LINE.test(normalized);
    })
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim()
    .slice(0, 12_000);
}
