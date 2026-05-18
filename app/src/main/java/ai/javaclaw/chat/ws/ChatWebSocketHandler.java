package ai.javaclaw.chat.ws;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import ai.javaclaw.chat.ChatChannel;
import ai.javaclaw.chat.ChatHtml;
import ai.javaclaw.chat.Htmx;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "javaclaw.chat.transport", havingValue = "spring-websocket", matchIfMissing = true)
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ChatChannel chatChannel;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(ChatChannel chatChannel, ObjectMapper objectMapper) {
        this.chatChannel = chatChannel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        chatChannel.setWsSession(session);
        log.info("WebChat WebSocket connected: {}", session.getId());

        List<String> ids = chatChannel.conversationIds();
        String selectedId = ids.getFirst();

        String conversationSelector = ChatHtml.conversationSelector(ids, selectedId);
        String bubbles = String.join(System.lineSeparator(), chatChannel.loadHistoryAsHtml(selectedId));
        String inputArea = ChatHtml.chatInputArea(selectedId);
        chatChannel.sendHtml(
                Htmx.oobInnerHtml("channel-selector", conversationSelector),
                Htmx.oobInnerHtml("chat-messages", bubbles),
                Htmx.oobInnerHtml("chat-input-area", inputArea));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        chatChannel.clearWsSession(session);
        log.info("WebChat WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(message.getPayload(), Map.class);
        String type = (String) payload.get("type");

        if ("channelChanged".equals(type)) {
            handleChannelChanged(payload);
        } else if ("userMessage".equals(type)) {
            handleUserMessage(payload);
        }
    }

    private void handleChannelChanged(Map<String, Object> payload) throws Exception {
        String conversationId = (String) payload.get("conversationId");
        if (conversationId == null || conversationId.isBlank()) return;

        String bubbles = String.join(System.lineSeparator(), chatChannel.loadHistoryAsHtml(conversationId));
        String inputArea = ChatHtml.chatInputArea(conversationId);
        chatChannel.sendHtml(
                Htmx.oobInnerHtml("chat-messages", bubbles),
                Htmx.oobInnerHtml("chat-input-area", inputArea));
    }

    private void handleUserMessage(Map<String, Object> payload) throws Exception {
        String conversationId = (String) payload.get("conversationId");
        String userMessage = (String) payload.get("message");

        if (userMessage == null || userMessage.isBlank()) return;
        userMessage = userMessage.trim();
        if (conversationId == null || conversationId.isBlank()) conversationId = "web";

        final String finalConversationId = conversationId;
        final String finalUserMessage = userMessage;
        final String bubbleId = "msg-" + UUID.randomUUID();

        // Echo user message + show typing indicator + send empty agent bubble to stream into
        chatChannel.sendHtml(
                Htmx.oobAppend("chat-messages", ChatHtml.userBubble(finalUserMessage)),
                Htmx.oobReplace("typing-indicator", ChatHtml.typingDots()),
                Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(bubbleId, "")));

        final StringBuilder accumulated = new StringBuilder();
        chatChannel.streamChat(finalConversationId, finalUserMessage)
                .subscribe(
                        chunk -> {
                            accumulated.append(chunk);
                            try {
                                chatChannel.sendHtml(
                                        Htmx.oobInnerHtml(bubbleId, accumulated.toString()));
                            } catch (Exception e) {
                                log.warn("Failed to push streaming chunk over WebSocket", e);
                            }
                        },
                        error -> {
                            log.warn("Chat stream failed for conversation {}", finalConversationId, error);
                            try {
                                chatChannel.sendHtml(
                                        Htmx.oobAppend("chat-messages", ChatHtml.agentBubble(genericUserFacingError(error))),
                                        Htmx.oobReplace("typing-indicator", ""));
                            } catch (Exception e) {
                                log.warn("Failed to push error message over WebSocket", e);
                            }
                        },
                        () -> {
                            try {
                                chatChannel.sendHtml(Htmx.oobReplace("typing-indicator", ""));
                            } catch (Exception e) {
                                log.warn("Failed to clear typing indicator over WebSocket", e);
                            }
                        }
                );
    }

    private static String genericUserFacingError(Throwable ex) {
        return "An error occurred while contacting the AI provider.\nDetails: " + summarizeError(ex);
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }

        return message;
    }
}