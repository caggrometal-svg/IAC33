import { sanitizeAssistantText } from "./sanitizeAssistantText";

export type ChatRole = "system" | "user" | "assistant";
export type ChatSource = "remote" | "local";

export interface ChatMessage {
  id: string;
  role: ChatRole;
  content: string;
  source?: ChatSource;
}

export interface AiResponse {
  ok: true;
  provider: string;
  model: string;
  text: string;
}

export interface AiClientOptions {
  baseUrl: string;
  timeoutMs?: number;
  maxAttempts?: number;
  fetchImpl?: typeof fetch;
  localFallback?: (messages: ChatMessage[]) => string;
}

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException("Aborted", "AbortError"));
      return;
    }
    const timer = window.setTimeout(resolve, ms);
    signal.addEventListener(
      "abort",
      () => {
        window.clearTimeout(timer);
        reject(new DOMException("Aborted", "AbortError"));
      },
      { once: true },
    );
  });
}

function isRetryable(status?: number): boolean {
  return !status || [408, 425, 429, 500, 502, 503, 504].includes(status);
}

function classifyIntent(text: string): "local" | "general" {
  const normalized = text.toLowerCase();
  return /\b(gps|ubicaci[oó]n|coordenadas|mapa|sismo|sismos|terremoto|multimedia|video|v[ií]deo|foto|imagen|config|configuraci[oó]n|estado|conectividad|internet|red)\b/.test(normalized)
    ? "local"
    : "general";
}

export async function generateIac33Answer(
  messages: ChatMessage[],
  options: AiClientOptions,
  signal: AbortSignal,
): Promise<{ text: string; source: ChatSource; provider: string; model: string }> {
  const fetchImpl = options.fetchImpl ?? fetch;
  const timeoutMs = Math.min(Math.max(options.timeoutMs ?? 10_000, 2_000), 45_000);
  const maxAttempts = Math.min(Math.max(options.maxAttempts ?? 3, 1), 3);
  const lastUser = [...messages].reverse().find((m) => m.role === "user")?.content ?? "";

  // Local-capability intents avoid unnecessary remote latency.
  if (classifyIntent(lastUser) === "local" && options.localFallback) {
    return {
      text: sanitizeAssistantText(options.localFallback(messages)),
      source: "local",
      provider: "local-fallback",
      model: "iac33-local-assistant",
    };
  }

  const deadline = Date.now() + timeoutMs;
  let lastError: unknown = null;

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (signal.aborted) throw new DOMException("Aborted", "AbortError");
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;

    const controller = new AbortController();
    const relayAbort = () => controller.abort(signal.reason);
    signal.addEventListener("abort", relayAbort, { once: true });
    const timer = window.setTimeout(() => controller.abort(), Math.min(5_000, remaining));

    try {
      const response = await fetchImpl(
        `${options.baseUrl.replace(/\/$/, "")}/v1/ai/generate`,
        {
          method: "POST",
          headers: { "content-type": "application/json", accept: "application/json" },
          body: JSON.stringify({
            conversationId: crypto.randomUUID(),
            messages: messages.map(({ role, content }) => ({ role, content })),
            timeoutMs: Math.min(5_000, remaining),
          }),
          signal: controller.signal,
        },
      );

      const body = (await response.json().catch(() => ({}))) as Partial<AiResponse> & { error?: string };
      if (!response.ok) {
        const error = new Error(body.error || `AI HTTP ${response.status}`);
        (error as Error & { status?: number }).status = response.status;
        throw error;
      }

      const text = sanitizeAssistantText(body.text);
      if (!text) throw new Error("EMPTY_AI_RESPONSE");

      return {
        text,
        source: "remote",
        provider: body.provider || "iac33-free-pool",
        model: body.model || "free-pool",
      };
    } catch (error) {
      lastError = error;
      const status = (error as Error & { status?: number }).status;
      if (error instanceof DOMException && error.name === "AbortError") throw error;
      if (!isRetryable(status) || attempt === maxAttempts) break;

      const backoff = Math.min(1_200, 200 * 2 ** (attempt - 1));
      await sleep(Math.min(backoff, Math.max(1, deadline - Date.now())), signal);
    } finally {
      window.clearTimeout(timer);
      signal.removeEventListener("abort", relayAbort);
    }
  }

  if (options.localFallback) {
    return {
      text: sanitizeAssistantText(options.localFallback(messages)),
      source: "local",
      provider: "local-fallback",
      model: "iac33-local-assistant",
    };
  }

  throw lastError instanceof Error ? lastError : new Error("AI_UNAVAILABLE");
}
