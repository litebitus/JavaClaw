package ai.javaclaw.channels.discord;

import static java.util.Optional.ofNullable;
import java.util.concurrent.atomic.AtomicReference;
import static java.util.regex.Pattern.quote;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import reactor.core.publisher.Flux;

public class DiscordChannel extends ListenerAdapter implements Channel {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannel.class);

    private final String allowedUserId;
    private final Agent agent;
    private final ChannelRegistry channelRegistry;
    private volatile MessageChannel lastChannel;

    public DiscordChannel(String allowedUserId, Agent agent, ChannelRegistry channelRegistry) {
        this.allowedUserId = normalizeUserId(allowedUserId);
        this.agent = agent;
        this.channelRegistry = channelRegistry;
        channelRegistry.registerChannel(this);
        log.info("Started Discord integration");
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (!shouldHandle(event)) {
            return;
        }

        String userId = normalizeUserId(event.getAuthor().getId());
        MessageChannel channel = event.getChannel();
        String content = normalizeText(event.getJDA(), event.getMessage(), event.isFromGuild());

        if (content == null) {
            return;
        }

        if (!isAllowedUser(userId)) {
            log.warn("Ignoring Discord message from unauthorized user '{}'", userId);
            reply(channel, "I'm sorry, I don't accept instructions from you.");
            return;
        }

        lastChannel = channel;
        channelRegistry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent(getName(), content));
        streamAndSend(channel, getConversationId(channel.getId()), content);
    }

    private void streamAndSend(MessageChannel channel, String conversationId, String content) {
        AtomicReference<Message> sentMessage = new AtomicReference<>();
        StringBuilder accumulated = new StringBuilder();
        AtomicReference<String> lastSentText = new AtomicReference<>();

        Flux<String> stream = agent.streamResponseTo(conversationId, content);
        stream.subscribe(
                chunk -> {
                    accumulated.append(chunk);
                    String current = accumulated.toString();
                    Message existing = sentMessage.get();
                    if (existing == null) {
                        // Send the first message synchronously so we have a reference to edit later
                        Message sent = channel.sendMessage(current).complete();
                        sentMessage.set(sent);
                        lastSentText.set(current);
                    } else {
                        existing.editMessage(current).queue();
                        lastSentText.set(current);
                    }
                },
                error -> {
                    log.error("Error while streaming response", error);
                    reply(channel, "Sorry, an error occurred while generating the response.");
                },
                () -> {
                    // Only do a final edit if new content arrived after the last send/edit
                    Message existing = sentMessage.get();
                    String finalText = accumulated.toString();
                    if (finalText.equals(lastSentText.get()))
                        return;
                    if (existing == null) {
                        reply(channel, finalText);
                    } else {
                        existing.editMessage(finalText).queue();
                    }
                });
    }

    @Override
    public void sendMessage(String message) {
        MessageChannel channel = lastChannel;
        if (channel == null) {
            log.error("No known Discord channel, cannot send message '{}'", message);
            return;
        }
        reply(channel, message);
    }

    private boolean shouldHandle(MessageReceivedEvent event) {
        User author = event.getAuthor();
        if (author.isBot() || event.isWebhookMessage()) {
            return false;
        }
        return event.isFromType(ChannelType.PRIVATE)
                || event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser());
    }

    private boolean isAllowedUser(String userId) {
        return userId != null && userId.equalsIgnoreCase(allowedUserId);
    }

    private static void reply(MessageChannel channel, String text) {
        channel.sendMessage(text).queue();
    }

    private static String normalizeText(JDA jda, Message message, boolean guildMessage) {
        String content = message.getContentRaw();
        if (content == null) {
            return null;
        }
        if (guildMessage) {
            String mention = ofNullable(jda.getSelfUser()).map(User::getAsMention).orElse("");
            content = content.replaceFirst("^\\s*" + quote(mention) + "\\s*", "");
        }
        content = content.trim();
        return content.isBlank() ? null : content;
    }

    private static String getConversationId(String channelId) {
        return "discord-" + channelId;
    }

    private static String normalizeUserId(String userId) {
        if (userId == null) {
            return null;
        }
        String normalized = userId.trim();
        if (normalized.startsWith("<@") && normalized.endsWith(">")) {
            normalized = normalized.substring(2, normalized.length() - 1);
            if (normalized.startsWith("!")) {
                normalized = normalized.substring(1);
            }
        }
        return normalized.isBlank() ? null : normalized;
    }
}
