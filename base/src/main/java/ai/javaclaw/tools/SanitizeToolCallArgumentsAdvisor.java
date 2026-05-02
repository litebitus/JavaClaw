package ai.javaclaw.tools;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Advisor that sanitizes tool call arguments by re-parsing JSON with a lenient
 * Jackson reader (allowing unescaped control characters) and re-serializing it.
 *
 * Some AI providers (e.g. AWS Bedrock Anthropic) return tool call arguments
 * containing literal newlines or other control characters inside JSON string
 * values, which Jackson's strict parser rejects with:
 *   "Illegal unquoted character (CTRL-CHAR, code 10)"
 *
 * This advisor must run at a higher order than ToolCallAdvisor (default order=0)
 * so that it intercepts the model response before ToolCallAdvisor processes
 * the tool calls.
 */
public class SanitizeToolCallArgumentsAdvisor implements CallAdvisor {

    private static final JsonMapper LENIENT_MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build();

    @Override
    public String getName() {
        return "SanitizeToolCallArgumentsAdvisor";
    }

    @Override
    public int getOrder() {
        return 1; // higher than ToolCallAdvisor (order=0): intercepts response first
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return sanitize(chain.nextCall(request));
    }

    private ChatClientResponse sanitize(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) return response;

        List<Generation> sanitizedGenerations = chatResponse.getResults().stream()
                .map(this::sanitizeGeneration)
                .toList();

        return response.mutate()
                .chatResponse(new ChatResponse(sanitizedGenerations, chatResponse.getMetadata()))
                .build();
    }

    private Generation sanitizeGeneration(Generation generation) {
        AssistantMessage msg = generation.getOutput();
        if (!msg.hasToolCalls()) return generation;

        List<AssistantMessage.ToolCall> sanitizedCalls = msg.getToolCalls().stream()
                .map(tc -> new AssistantMessage.ToolCall(tc.id(), tc.type(), tc.name(), sanitizeJson(tc.arguments())))
                .toList();

        AssistantMessage sanitized = AssistantMessage.builder()
                .content(msg.getText())
                .properties(msg.getMetadata())
                .toolCalls(sanitizedCalls)
                .build();

        return new Generation(sanitized, generation.getMetadata());
    }

    private String sanitizeJson(String json) {
        if (json == null || json.isEmpty()) return json;
        try {
            Object parsed = LENIENT_MAPPER.readValue(json, Object.class);
            return LENIENT_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            return json;
        }
    }
}
