package ai.javaclaw.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SanitizeToolCallArgumentsAdvisorTest {

    private SanitizeToolCallArgumentsAdvisor advisor;
    private CallAdvisorChain chain;

    @BeforeEach
    void setUp() {
        advisor = new SanitizeToolCallArgumentsAdvisor();
        chain = mock(CallAdvisorChain.class);
    }

    @Test
    void sanitizesLiteralNewlineInToolCallArgument() {
        String dirtyJson = "{\"arg0\": \"line one\nline two\"}";
        ChatClientResponse response = responseWithToolCall("call-1", "myTool", dirtyJson);
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(emptyRequest(), chain);

        String sanitized = toolCallArguments(result, 0);
        assertThat(sanitized).doesNotContain("\n");
        assertThat(sanitized).contains("\\n");
        assertThat(sanitized).contains("line one");
        assertThat(sanitized).contains("line two");
    }

    @Test
    void sanitizesLiteralCarriageReturnAndTab() {
        String dirtyJson = "{\"arg0\": \"col1\tcol2\r\ncol3\"}";
        ChatClientResponse response = responseWithToolCall("call-1", "myTool", dirtyJson);
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(emptyRequest(), chain);

        String sanitized = toolCallArguments(result, 0);
        assertThat(sanitized).doesNotContain("\t").doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void leavesCleanJsonUnchanged() {
        String cleanJson = "{\"arg0\": \"hello world\"}";
        ChatClientResponse response = responseWithToolCall("call-1", "myTool", cleanJson);
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(emptyRequest(), chain);

        String sanitized = toolCallArguments(result, 0);
        assertThat(sanitized).contains("hello world");
    }

    @Test
    void passesResponseThroughWhenNoToolCalls() {
        AssistantMessage msg = new AssistantMessage("just text");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).context(Map.of()).build();
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(emptyRequest(), chain);

        assertThat(result.chatResponse().getResult().getOutput().getText()).isEqualTo("just text");
    }

    @Test
    void handlesMultipleToolCallsInSingleResponse() {
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("c1", "function", "tool1", "{\"arg0\": \"a\nb\"}"),
                new AssistantMessage.ToolCall("c2", "function", "tool2", "{\"arg0\": \"c\nd\"}")
        );
        AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(calls).build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(chatResponse).context(Map.of()).build();
        when(chain.nextCall(any())).thenReturn(response);

        ChatClientResponse result = advisor.adviseCall(emptyRequest(), chain);

        AssistantMessage sanitizedMsg = result.chatResponse().getResult().getOutput();
        assertThat(sanitizedMsg.getToolCalls()).hasSize(2);
        assertThat(sanitizedMsg.getToolCalls().get(0).arguments()).doesNotContain("\n");
        assertThat(sanitizedMsg.getToolCalls().get(1).arguments()).doesNotContain("\n");
    }

    @Test
    void orderIsGreaterThanZero() {
        assertThat(advisor.getOrder()).isGreaterThan(0);
    }

    private ChatClientResponse responseWithToolCall(String id, String name, String arguments) {
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall(id, "function", name, arguments)
        );
        AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(calls).build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(msg)));
        return ChatClientResponse.builder().chatResponse(chatResponse).context(Map.of()).build();
    }

    private String toolCallArguments(ChatClientResponse response, int index) {
        return response.chatResponse().getResult().getOutput().getToolCalls().get(index).arguments();
    }

    private ChatClientRequest emptyRequest() {
        return ChatClientRequest.builder().prompt(new Prompt(List.of())).build();
    }
}
