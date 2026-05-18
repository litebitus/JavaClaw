# LLM Response Streaming

JavaClaw streams LLM responses token-by-token across all channels rather than waiting for the full response before delivery. This keeps every channel handler thread free to accept new messages immediately and gives the user live feedback as the model generates its reply.

## How It Works

### Agent interface

`Agent.streamResponseTo(conversationId, question)` returns a `Flux<String>` backed by Spring AI's `.stream().content()`. Each element is a raw text token as it arrives from the provider.

The blocking `respondTo` method is still available and is used where a complete string is required (e.g. structured `prompt(...)` calls for task execution).

### Telegram

1. The long-polling consumer thread calls `streamAndSend` and returns immediately.
2. The **first token** triggers a `SendMessage` to Telegram. The returned message ID is saved.
3. Each **subsequent token** calls `EditMessageText` on that message ID, updating the bubble in place.
4. On **completion**, a final edit is issued only if the accumulated text has changed since the last send or edit (avoids a redundant API call on single-chunk responses).
5. If the HTML-formatted send fails, the plain-text fallback is used once. `lastSentText` is set *before* the send attempt so the completion handler does not retry.

### Discord

Follows the same pattern using JDA:

1. The **first token** is sent with `channel.sendMessage(...).complete()` (synchronous so the `Message` reference is available for subsequent edits).
2. Each **subsequent token** calls `message.editMessage(...).queue()`.
3. On **completion**, a final edit is issued only if the text has changed since the last send or edit.

### Web Chat (WebSocket)

1. On receiving a message the handler immediately pushes the user bubble, the typing indicator, and an **empty agent bubble** (with a unique ID) to the client, then returns.
2. Each token is pushed as an `hx-swap-oob="innerHTML"` fragment that targets the bubble's inner `<div>` by ID, replacing its content with the growing accumulated text.
3. On **completion**, the typing indicator is cleared via an OOB replace.
4. On **error**, a new error bubble is appended and the typing indicator is cleared.

## Deduplication on Completion

All three channels track `lastSentText`. The `onComplete` callback skips its final send/edit when `accumulated.toString().equals(lastSentText.get())`. This prevents a duplicate API call in the common case where the last `onNext` chunk already delivered the complete response.

## Dependencies

`reactor-core` is declared explicitly in `plugins/telegram/build.gradle` and `plugins/discord/build.gradle`. The `app` module inherits it transitively via `spring-boot-starter-webflux` conventions; `base` has it via `spring-ai-client-chat`.
