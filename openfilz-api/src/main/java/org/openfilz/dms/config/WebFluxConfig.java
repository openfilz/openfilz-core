
package org.openfilz.dms.config;

import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.converter.CustomJacksonPartArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

/**
 * Configuration WebFlux et codecs JSON.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableWebFlux
public class WebFluxConfig implements WebFluxConfigurer {

    private final JsonMapper objectMapper;
    private final CustomJacksonPartArgumentResolver customJacksonPartArgumentResolver;

    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {

        configurer.defaultCodecs().jacksonJsonEncoder(new JacksonJsonEncoder(objectMapper));
        configurer.defaultCodecs().jacksonJsonDecoder(new JacksonJsonDecoder(objectMapper));
        // Registered before the default ResourceHttpMessageWriter: Range on a stored-object stream → full 200, not 416
        configurer.customCodecs().register(new OneShotStreamResourceWriter());

        log.info("WebFlux codecs configured with custom ObjectMapper");
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(customJacksonPartArgumentResolver);
    }
}
