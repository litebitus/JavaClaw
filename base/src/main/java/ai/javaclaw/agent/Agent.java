package ai.javaclaw.agent;

import reactor.core.publisher.Flux;

public interface Agent {

    String respondTo(String conversationId, String question);

    Flux<String> streamResponseTo(String conversationId, String question);

    <T> T prompt(String conversationId, String input, Class<T> result);

}
