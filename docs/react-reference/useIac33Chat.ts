import { useCallback, useEffect, useRef, useState } from "react";
import {
  type AiClientOptions,
  type ChatMessage,
  generateIac33Answer,
} from "./iac33AiClient";

function createId(): string {
  return crypto.randomUUID();
}

export function useIac33Chat(options: AiClientOptions) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [busy, setBusy] = useState(false);
  const controllerRef = useRef<AbortController | null>(null);

  const cancel = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    setBusy(false);
  }, []);

  const send = useCallback(
    async (text: string) => {
      const content = text.trim();
      if (!content || busy) return;

      cancel();
      const controller = new AbortController();
      controllerRef.current = controller;

      const userMessage: ChatMessage = {
        id: createId(),
        role: "user",
        content,
        source: "local",
      };

      const nextMessages = [...messages, userMessage];
      setMessages(nextMessages);
      setBusy(true);

      try {
        const result = await generateIac33Answer(nextMessages, options, controller.signal);
        setMessages((current) => [
          ...current,
          {
            id: createId(),
            role: "assistant",
            content: result.text,
            source: result.source,
          },
        ]);
      } finally {
        if (controllerRef.current === controller) {
          controllerRef.current = null;
          setBusy(false);
        }
      }
    },
    [busy, cancel, messages, options],
  );

  useEffect(() => () => controllerRef.current?.abort(), []);

  return { messages, busy, send, cancel };
}
