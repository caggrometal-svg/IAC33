import type { ChatMessage } from "./iac33AiClient";
import { sanitizeAssistantText } from "./sanitizeAssistantText";

interface ChatBubbleProps {
  message: ChatMessage;
}

export function ChatBubble({ message }: ChatBubbleProps) {
  const isUser = message.role === "user";
  const source = message.source === "local" ? "Respaldo Local" : "Nodo Remoto";

  return (
    <article
      className={`iac33-chat-bubble ${isUser ? "iac33-chat-bubble--user" : "iac33-chat-bubble--assistant"}`}
      aria-label={isUser ? "Mensaje del usuario" : `Respuesta de IAC33: ${source}`}
    >
      <header className="iac33-chat-bubble__header">
        <strong>{isUser ? "TÚ" : "IAC33"}</strong>
        {!isUser && (
          <span className="iac33-chat-bubble__badge" data-source={message.source}>
            {source}
          </span>
        )}
      </header>
      <div className="iac33-chat-bubble__content">
        {sanitizeAssistantText(message.content)}
      </div>
    </article>
  );
}
