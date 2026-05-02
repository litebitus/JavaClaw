package ai.javaclaw.providers.bedrock.anthropic;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.tool.DefaultToolExecutionEligibilityPredicate;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityPredicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;

@Configuration
@ConditionalOnProperty("spring.ai.bedrock.aws.profile.name")
public class BedrockAnthropicConfiguration {

    @Value("${spring.ai.bedrock.aws.profile.name}")
    private String awsProfile;

    @Value("${spring.ai.bedrock.aws.region:}")
    private String awsRegion;

    @Value("${spring.ai.bedrock.converse.chat.options.model:us.anthropic.claude-sonnet-4-6}")
    private String model;

    @Bean
    public BedrockProxyChatModel bedrockProxyChatModel(
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatModelObservationConvention> observationConvention,
            ObjectProvider<ToolExecutionEligibilityPredicate> toolExecutionEligibilityPredicate) {

        var builder = BedrockProxyChatModel.builder()
                .credentialsProvider(ProfileCredentialsProvider.create(awsProfile));

        if (!awsRegion.isBlank()) {
            builder.region(Region.of(awsRegion));
        }

        var chatModel = builder
                .defaultOptions(BedrockChatOptions.builder().model(model).build())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .toolExecutionEligibilityPredicate(toolExecutionEligibilityPredicate
                        .getIfUnique(DefaultToolExecutionEligibilityPredicate::new))
                .build();

        observationConvention.ifAvailable(chatModel::setObservationConvention);

        return chatModel;
    }
}
