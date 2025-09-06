package org.openfilz.dms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;

@Configuration
@ConditionalOnProperty(name = "spring.security.cors-allowed-origins")
@EnableWebFlux
public class CorsConfig implements WebFluxConfigurer {

    @Value("${spring.security.cors-allowed-origins}")
    private String[] allowedOrigins;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    @Override
    public void addCorsMappings(CorsRegistry corsRegistry) {

        corsRegistry.addMapping(API_PREFIX + "/**")
          .allowedOrigins(allowedOrigins)
                .allowedMethods(HttpMethod.GET.name(),
                        HttpMethod.POST.name(),
                        HttpMethod.PUT.name(),
                        HttpMethod.PATCH.name(),
                        HttpMethod.DELETE.name());
        corsRegistry.addMapping(graphQlBaseUrl + "/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(HttpMethod.POST.name(),
                        HttpMethod.OPTIONS.name())
        ;
    }
}