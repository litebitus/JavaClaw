package ai.javaclaw.providers.bedrock.anthropic;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BedrockAnthropicAgentOnboardingProvider implements AgentOnboardingProvider {

    @Override
    public String getId() {
        return "bedrock-anthropic";
    }

    @Override
    public String getLabel() {
        return "Anthropic (AWS Bedrock)";
    }

    @Override
    public String slogan() {
        return "Uses AWS Bedrock to run Claude models with your AWS profile credentials.";
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String defaultModel() {
        return "us.anthropic.claude-sonnet-4-6";
    }

    @Override
    public List<ExtraField> extraFields() {
        return List.of(
                new ExtraField("awsProfile", "AWS Profile", "e.g. my-aws-profile", true,
                        "spring.ai.bedrock.aws.profile.name"),
                new ExtraField("awsRegion", "AWS Region", "uses profile default if blank", false,
                        "spring.ai.bedrock.aws.region")
        );
    }
}
