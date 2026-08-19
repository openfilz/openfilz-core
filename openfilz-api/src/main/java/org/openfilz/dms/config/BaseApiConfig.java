package org.openfilz.dms.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Configuration
public class BaseApiConfig {

    @Bean
    @Primary
    public JsonMapper objectMapper(List<JsonMapperCustomizer> customizers) {
        // Jackson 3: java.time support is built-in (no JavaTimeModule needed),
        // mappers are immutable and configured via the builder.
        // JsonMapperCustomizer beans let editions/extensions contribute configuration
        // (e.g. extra @JsonSubTypes) before the immutable mapper is built.
        JsonMapper.Builder builder = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL));
        customizers.forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

}
