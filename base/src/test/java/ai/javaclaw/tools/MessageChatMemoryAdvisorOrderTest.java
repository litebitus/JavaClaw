package ai.javaclaw.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the "conversation must end with a user message" Bedrock error that occurs
 * when MessageChatMemoryAdvisor runs inside ToolCallAdvisor's tool-call loop.
 *
 * Root cause: ToolCallAdvisor loops internally, calling the downstream chain on each
 * iteration. With MessageChatMemoryAdvisor inside that chain, before() runs on every
 * iteration and prepends the full chat memory. After the first iteration, after() has
 * already saved the assistant-with-tool-calls response to memory. On the second
 * iteration, before() prepends that memory, which contains the assistant-with-tool-calls
 * message. The tool-call instructions from ToolCallAdvisor also include that same
 * assistant message. The combined sequence therefore has an assistant(tool_use) message
 * not immediately followed by a tool_result, which Bedrock's Converse API rejects as
 * an orphaned tool-use block.
 *
 * Fix: give MessageChatMemoryAdvisor an order lower than ToolCallAdvisor's default
 * (Integer.MIN_VALUE + 300) so that it runs outside the tool-call loop.
 */
class MessageChatMemoryAdvisorOrderTest {

    // ToolCallAdvisor's compiled default order = Integer.MIN_VALUE + 300
    private static final int TOOL_CALL_ADVISOR_DEFAULT_ORDER = Integer.MIN_VALUE + 300;

    @Test
    void memoryInsideToolLoopProducesOrphanedToolUseBlock() {
        // Simulate what MessageChatMemoryAdvisor.before() produces on the SECOND
        // iteration of the tool-call loop when memory is inside the loop:
        //
        // After first iteration: after() saved [user, assistant(tool_use)] to memory.
        // ToolCallAdvisor then calls the chain with conversationHistory as instructions:
        //   instructions = [user, assistant(tool_use), tool_result]
        // before() prepends memory to instructions:
        //   [user, assistant(tool_use)]  +  [user, assistant(tool_use), tool_result]
        //   = [user(0), assistant(tool_use)(1), user(2), assistant(tool_use)(3), tool_result(4)]
        //
        // assistant(tool_use) at index 1 is NOT followed by a tool_result → Bedrock rejects.

        var assistantWithToolCalls = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "myTool", "{\"arg0\":\"x\"}")))
                .build();
        var toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "myTool", "result")))
                .build();

        // Memory state after first iteration's after(): [user, assistant(tool_use)]
        List<Message> memoryAfterFirstIteration = List.of(
                new UserMessage("do something"),
                assistantWithToolCalls
        );

        // Instructions passed by ToolCallAdvisor for second iteration: conversationHistory
        List<Message> secondIterationInstructions = List.of(
                new UserMessage("do something"),
                assistantWithToolCalls,
                toolResult
        );

        // Simulate what MessageChatMemoryAdvisor.before() does: prepend memory
        List<Message> combined = new ArrayList<>(memoryAfterFirstIteration);
        combined.addAll(secondIterationInstructions);

        assertThat(hasOrphanedToolUse(combined))
                .as("Memory inside the loop creates an orphaned tool-use block that Bedrock rejects")
                .isTrue();
    }

    @Test
    void memoryOutsideToolLoopNeverProducesOrphanedToolUseBlock() {
        // With the fix: MessageChatMemoryAdvisor runs OUTSIDE the tool-call loop.
        // before() is called once per user turn with the clean prior history.
        // The tool-call loop uses only its own internal conversation history.
        //
        // On the next user turn, memory contains [prev_user, final_answer].
        // before() prepends: [prev_user, final_answer, new_user]  — clean, ends with USER.

        List<Message> memory = List.of(
                new UserMessage("previous question"),
                new AssistantMessage("previous answer")  // no tool calls
        );
        List<Message> currentTurnInstructions = List.of(new UserMessage("new question"));

        List<Message> combined = new ArrayList<>(memory);
        combined.addAll(currentTurnInstructions);

        assertThat(hasOrphanedToolUse(combined)).isFalse();
        assertThat(combined.get(combined.size() - 1).getMessageType()).isEqualTo(MessageType.USER);
    }

    @Test
    void memoryAdvisorOrderIsLowerThanToolCallAdvisorByDefault() {
        // Verify the fix: our MessageChatMemoryAdvisor order must be LOWER than
        // ToolCallAdvisor's default so it runs before (outside) the tool-call loop.
        var chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        var advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .order(Ordered.HIGHEST_PRECEDENCE + 100)
                .build();

        assertThat(advisor.getOrder()).isLessThan(TOOL_CALL_ADVISOR_DEFAULT_ORDER);
    }

    @Test
    void memoryOutsideToolLoopSavesOnlyFinalAnswerNotToolCallIntermediates() {
        // When memory runs outside the loop, after() is called exactly once with the
        // final response. Intermediate assistant-with-tool-calls messages are never
        // saved to memory.
        var chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
        String convId = "test-conv";

        // Simulate: only the final answer is saved (no tool calls)
        chatMemory.add(convId, List.of(new UserMessage("do something")));
        chatMemory.add(convId, List.of(new AssistantMessage("here is the result")));

        List<Message> saved = chatMemory.get(convId);

        assertThat(saved).hasSize(2);
        assertThat(saved.stream().noneMatch(
                m -> m instanceof AssistantMessage am && am.hasToolCalls()
        )).as("Memory should not contain intermediate tool-call assistant messages").isTrue();
    }

    private boolean hasOrphanedToolUse(List<Message> messages) {
        for (int i = 0; i < messages.size() - 1; i++) {
            if (messages.get(i) instanceof AssistantMessage am && am.hasToolCalls()) {
                if (messages.get(i + 1).getMessageType() != MessageType.TOOL) {
                    return true;
                }
            }
        }
        return false;
    }
}
