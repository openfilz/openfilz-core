package org.openfilz.dms.e2e;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@TestConfiguration
public class GraphQlTestConfig {

    /**
     * Creates a custom JsonMapper to serialize OffsetDateTime as ISO-8601 strings.
     */
    @Bean
    public JsonMapper customGraphQlObjectMapper() {
        // Jackson 3: java.time support is built-in; the key setting is to
        // disable writing dates as epoch timestamps
        return JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Creates a custom encoder using the configured JsonMapper.
     */
    @Bean
    public JacksonJsonEncoder customJacksonJsonEncoder(JsonMapper customGraphQlObjectMapper) {
        // Pass the custom JsonMapper to the encoder
        return new JacksonJsonEncoder(customGraphQlObjectMapper);
    }
}
