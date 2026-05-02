package ai.javaclaw.onboarding.steps;

import ai.javaclaw.onboarding.AgentOnboardingProvider;
import ai.javaclaw.onboarding.AgentOnboardingProvider.ExtraField;
import ai.javaclaw.onboarding.AgentOnboardingProvider.SystemWideToken;
import ai.javaclaw.onboarding.AgentOnboardingProviders;
import ai.javaclaw.onboarding.OnboardingProvider;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(30)
public class S3_CredentialsStep implements OnboardingProvider {

    private final Environment env;
    private final AgentOnboardingProviders agentOnboardingProviders;

    public S3_CredentialsStep(Environment env, AgentOnboardingProviders agentOnboardingProviders) {
        this.env = env;
        this.agentOnboardingProviders = agentOnboardingProviders;
    }

    @Override
    public String getStepId() {return "credentials";}

    @Override
    public String getStepTitle() {return "Credentials";}

    @Override
    public String getTemplatePath() {return "onboarding/steps/S3-credentials";}

    @Override
    public void prepareModel(Map<String, Object> session, Map<String, Object> model) {
        String providerId = (String) session.getOrDefault(S2_ProviderStep.SESSION_PROVIDER, env.getProperty("spring.ai.model.chat", ""));
        AgentOnboardingProvider provider = agentOnboardingProviders.findById(providerId).orElse(null);
        if (provider == null) return;

        String currentModel = (String) session.get(S2_ProviderStep.SESSION_MODEL);
        String existingModel = env.getProperty(provider.createPropertyKey("chat.options.model"), "");
        String existingApiKey = env.getProperty(provider.createPropertyKey("api-key"), "");
        model.put("selectedProvider", provider.getId());
        model.put("providerLabel", provider.getLabel());
        model.put("providerApiPropertyKey", provider.createPropertyKey("api-key"));
        model.put("chatModelPropertyKey", provider.createPropertyKey("chat.options.model"));
        model.put("requiresApiKey", provider.requiresApiKey());
        model.put("apiKey", session.getOrDefault(S2_ProviderStep.SESSION_API_KEY, existingApiKey));
        model.put("model", currentModel != null && !currentModel.isBlank() ? currentModel : (!existingModel.isBlank() ? existingModel : provider.defaultModel()));
        provider.systemWideToken().ifPresent(t -> model.put("systemWideTokenName", t.name()));

        List<Map<String, Object>> extraFields = new ArrayList<>();
        for (ExtraField f : provider.extraFields()) {
            Map<String, Object> fieldMap = new LinkedHashMap<>();
            fieldMap.put("name", f.name());
            fieldMap.put("label", f.label());
            fieldMap.put("placeholder", f.placeholder());
            fieldMap.put("required", f.required());
            fieldMap.put("propertyKey", f.propertyKey());
            fieldMap.put("value", session.getOrDefault(S2_ProviderStep.SESSION_EXTRA_PREFIX + f.name(),
                    env.getProperty(f.propertyKey(), "")));
            extraFields.add(fieldMap);
        }
        model.put("extraFields", extraFields);
    }

    @Override
    public String processStep(Map<String, String> formParams, Map<String, Object> session) {
        String providerId = (String) session.getOrDefault(S2_ProviderStep.SESSION_PROVIDER, env.getProperty("spring.ai.model.chat", ""));
        AgentOnboardingProvider provider = agentOnboardingProviders.findById(providerId).orElse(null);
        if (provider == null) {
            return "Provider selection is missing. Please go back and select a provider.";
        }

        String model = formParams.getOrDefault("model", "").trim();
        String apiKey = formParams.getOrDefault("apiKey", "").trim();

        if (model.isBlank()) {
            return "Enter a model to continue.";
        }

        if ("true".equals(formParams.get("useSystemToken"))) {
            SystemWideToken sysToken = provider.systemWideToken().orElse(null);
            if (sysToken == null) {
                return "System token is no longer available. Please enter your API key manually.";
            }
            apiKey = sysToken.token();
        }

        if (provider.requiresApiKey() && apiKey.isBlank()) {
            return "Enter an API key to continue.";
        }

        for (ExtraField field : provider.extraFields()) {
            String value = formParams.getOrDefault(field.name(), "").trim();
            if (field.required() && value.isBlank()) {
                return "Enter " + field.label() + " to continue.";
            }
            session.put(S2_ProviderStep.SESSION_EXTRA_PREFIX + field.name(), value);
        }

        session.put(S2_ProviderStep.SESSION_MODEL, model);
        session.put(S2_ProviderStep.SESSION_API_KEY, apiKey);
        return null;
    }

    AgentOnboardingProvider getAgentProvider(Map<String, Object> session) {
        String providerId = (String) session.getOrDefault(S2_ProviderStep.SESSION_PROVIDER, env.getProperty("spring.ai.model.chat", ""));
        return agentOnboardingProviders.getById(providerId);
    }
}
