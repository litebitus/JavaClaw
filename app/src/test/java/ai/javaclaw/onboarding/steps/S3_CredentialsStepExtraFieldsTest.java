package ai.javaclaw.onboarding.steps;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProvider.ExtraField;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3_CredentialsStepExtraFieldsTest {

    @Mock
    private AgentOnboardingProviders agentOnboardingProviders;
    @Mock
    private Environment env;
    @InjectMocks
    private S3_CredentialsStep step;

    @Mock
    private AgentOnboardingProvider provider;

    private Map<String, Object> session;
    private Map<String, String> form;

    @BeforeEach
    void setUp() {
        session = new HashMap<>();
        session.put(S2_ProviderStep.SESSION_PROVIDER, "bedrock-anthropic");
        form = new HashMap<>();
        form.put("model", "us.anthropic.claude-sonnet-4-6");

        when(env.getProperty("spring.ai.model.chat", "")).thenReturn("");
        when(agentOnboardingProviders.findById("bedrock-anthropic")).thenReturn(Optional.of(provider));
        when(provider.requiresApiKey()).thenReturn(false);
        when(provider.extraFields()).thenReturn(List.of(
                new ExtraField("awsProfile", "AWS Profile", "e.g. my-profile", true,
                        "spring.ai.bedrock.aws.profile.name"),
                new ExtraField("awsRegion", "AWS Region", "uses profile default", false,
                        "spring.ai.bedrock.aws.region")
        ));
    }

    @Test
    void missingRequiredExtraFieldReturnsError() {
        String error = step.processStep(form, session);

        assertThat(error).contains("AWS Profile");
    }

    @Test
    void missingOptionalExtraFieldIsAccepted() {
        form.put("awsProfile", "my-profile");

        String error = step.processStep(form, session);

        assertThat(error).isNull();
    }

    @Test
    void extraFieldsAreSavedToSession() {
        form.put("awsProfile", "my-profile");
        form.put("awsRegion", "eu-west-1");

        step.processStep(form, session);

        assertThat(session).containsEntry("onboarding.extra.awsProfile", "my-profile");
        assertThat(session).containsEntry("onboarding.extra.awsRegion", "eu-west-1");
    }

    @Test
    void blankOptionalExtraFieldIsSavedAsEmptyString() {
        form.put("awsProfile", "my-profile");
        form.put("awsRegion", "");

        step.processStep(form, session);

        assertThat(session).containsEntry("onboarding.extra.awsRegion", "");
    }
}
