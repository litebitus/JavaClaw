package ai.javaclaw.providers.bedrock.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider.ExtraField;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BedrockAnthropicAgentOnboardingProviderTest {

    private BedrockAnthropicAgentOnboardingProvider provider;

    @BeforeEach
    void setUp() {
        provider = new BedrockAnthropicAgentOnboardingProvider();
    }

    @Test
    void doesNotRequireApiKey() {
        assertThat(provider.requiresApiKey()).isFalse();
    }

    @Test
    void defaultModelIsBedrockClaudeSonnet() {
        assertThat(provider.defaultModel()).isEqualTo("us.anthropic.claude-sonnet-4-6");
    }

    @Test
    void extraFieldsContainsAwsProfileAndRegion() {
        List<ExtraField> fields = provider.extraFields();

        assertThat(fields).hasSize(2);

        ExtraField profile = fields.get(0);
        assertThat(profile.name()).isEqualTo("awsProfile");
        assertThat(profile.required()).isTrue();
        assertThat(profile.propertyKey()).isEqualTo("spring.ai.bedrock.aws.profile.name");

        ExtraField region = fields.get(1);
        assertThat(region.name()).isEqualTo("awsRegion");
        assertThat(region.required()).isFalse();
        assertThat(region.propertyKey()).isEqualTo("spring.ai.bedrock.aws.region");
    }
}
