package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing the three "list models" responses, and the base-URL handling that decides where the
 * OpenAI-shaped one is fetched from.
 * <p>
 * These exercise the parsing directly rather than through HTTP: the network call is three lines of
 * WebClient, while the shape of each provider's answer — and what a malformed one must do — is the
 * part that decides whether the picker works.
 */
class AiModelDirectoryTest {

    private final AiModelDirectory directory = new AiModelDirectory(new ObjectMapper());

    private List<String> parseGoogle(String body) {
        return directory.parseGoogle(body);
    }

    private List<String> parseOpenAiShaped(String body) {
        return directory.parseOpenAiShaped(body);
    }

    private String modelsUrl(String baseUrl) {
        return AiModelDirectory.modelsUrl(baseUrl);
    }

    @Test
    @DisplayName("Google: the models/ prefix is stripped and only chat models are kept")
    void parsesGoogleListModels() {
        String body = """
                {"models":[
                  {"name":"models/gemini-3.6-flash","supportedGenerationMethods":["generateContent","countTokens"]},
                  {"name":"models/gemini-3.7-flash","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}
                ]}""";

        assertThat(parseGoogle(body)).containsExactly("gemini-3.6-flash", "gemini-3.7-flash");
    }

    @Test
    @DisplayName("a model that cannot generate content is dropped even when its id looks fine")
    void honoursSupportedGenerationMethods() {
        String body = """
                {"models":[{"name":"models/gemini-3.6-flash","supportedGenerationMethods":["countTokens"]}]}""";

        assertThat(parseGoogle(body)).isEmpty();
    }

    @Test
    @DisplayName("OpenAI and Anthropic share a response shape; their non-chat catalogue is filtered")
    void parsesOpenAiShapedListModels() {
        String openai = """
                {"data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"},{"id":"whisper-1"},
                         {"id":"dall-e-3"},{"id":"text-embedding-3-small"}]}""";
        assertThat(parseOpenAiShaped(openai)).containsExactly("gpt-4o", "gpt-4o-mini");

        assertThat(parseOpenAiShaped("""
                {"data":[{"id":"claude-opus-5"},{"id":"claude-haiku-4-5"}]}"""))
                .containsExactly("claude-opus-5", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("a provider answer we cannot read yields no models rather than an exception")
    void malformedResponsesDegradeQuietly() {
        assertThat(parseGoogle("{}")).isEmpty();
        assertThat(parseGoogle("{\"error\":{\"code\":403,\"message\":\"denied\"}}")).isEmpty();
        assertThat(parseGoogle("{\"models\":\"not-an-array\"}")).isEmpty();
        assertThat(parseGoogle("{\"models\":[{}]}")).isEmpty();
        assertThat(parseOpenAiShaped("{\"data\":[]}")).isEmpty();
    }

    @Test
    @DisplayName("/v1/models is appended once, however the gateway's base URL is written")
    void buildsTheModelsUrl() {
        assertThat(modelsUrl(null)).isEqualTo("https://api.openai.com/v1/models");
        assertThat(modelsUrl("   ")).isEqualTo("https://api.openai.com/v1/models");
        assertThat(modelsUrl("https://llm.internal")).isEqualTo("https://llm.internal/v1/models");
        assertThat(modelsUrl("https://llm.internal/")).isEqualTo("https://llm.internal/v1/models");
        // Both spellings are common for OpenAI-compatible gateways, so /v1 must not be doubled.
        assertThat(modelsUrl("https://llm.internal/v1")).isEqualTo("https://llm.internal/v1/models");
        assertThat(modelsUrl("https://llm.internal/v1/")).isEqualTo("https://llm.internal/v1/models");
    }
}
