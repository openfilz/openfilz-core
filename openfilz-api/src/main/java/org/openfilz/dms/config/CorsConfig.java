package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.util.Arrays;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "openfilz.security.cors-allowed-origins")
@EnableWebFlux
public class CorsConfig implements WebFluxConfigurer {

    @Value("${openfilz.security.cors-allowed-origins}")
    private String[] allowedOrigins;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    @PostConstruct
    public void init() {
        log.info("Created Cors bean with {}, {}", API_PREFIX, Arrays.toString(allowedOrigins));
    }

    @Override
    public void addCorsMappings(CorsRegistry corsRegistry) {
        if(allowedOrigins != null && allowedOrigins.length > 0) {
            // REST API
            corsRegistry.addMapping(API_PREFIX + "/**")
                    .allowedOrigins(allowedOrigins)
                    .allowedMethods(
                            HttpMethod.GET.name(),
                            HttpMethod.POST.name(),
                            HttpMethod.PUT.name(),
                            HttpMethod.PATCH.name(),
                            HttpMethod.DELETE.name(),
                            HttpMethod.OPTIONS.name()
                    );
            //GraphQL API
            corsRegistry.addMapping(graphQlBaseUrl + "/**")
                    .allowedOrigins(allowedOrigins)
                    .allowedMethods(
                            HttpMethod.POST.name(),
                            HttpMethod.OPTIONS.name()
                    );
        }
    }
}