package org.openfilz.dms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Configures Jackson to ignore unknown JSON properties globally.
 * This is required for compatibility with newer Ollama versions that return additional fields
 * (e.g., "id", "index" in tool_calls) that Spring AI's DTO classes don't declare.
 * <p>
 * Jackson 3 mappers are immutable, so the feature is contributed to the shared
 * {@link tools.jackson.databind.json.JsonMapper} builder through a {@link JsonMapperCustomizer}
 * rather than mutated after the fact. The WebFlux codecs already reuse that mapper
 * (see {@code WebFluxConfig}).
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperCustomizer ignoreUnknownPropertiesCustomizer() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
