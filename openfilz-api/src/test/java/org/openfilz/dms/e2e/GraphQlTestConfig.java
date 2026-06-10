package org.openfilz.dms.e2e;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

@TestConfiguration
public class GraphQlTestConfig {

    /**
     * WebTestClient bound to the running server.
     * <p>
     * Spring Boot 4 no longer auto-configures a server-bound WebTestClient for
     * {@code @SpringBootTest(webEnvironment = RANDOM_PORT/DEFINED_PORT)} — the new
     * {@code spring-boot-webtestclient} auto-configuration only provides a mock-bound
     * client. This bean restores the previous behavior for all ITs.
     * {@code @Lazy} is required: {@code local.server.port} is only available once the
     * web server has started, which happens after singleton instantiation.
     */
    @Bean
    @Lazy
    public WebTestClient webTestClient(Environment env) {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + env.getProperty("local.server.port"))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
    }

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
