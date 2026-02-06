package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "openfilz.security.cors-allowed-origins")
public class CorsConfig {

    @Value("${openfilz.security.cors-allowed-origins}")
    private String[] allowedOrigins;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    @PostConstruct
    public void init() {
        log.info("Created Cors bean with {}, {}", API_PREFIX, Arrays.toString(allowedOrigins));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        if (allowedOrigins != null && allowedOrigins.length > 0) {
            // REST API CORS configuration (includes TUS endpoints)
            CorsConfiguration apiConfig = new CorsConfiguration();
            apiConfig.setAllowedOrigins(Arrays.asList(allowedOrigins));
            apiConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
            // TUS protocol requires these headers to be allowed in requests
            apiConfig.setAllowedHeaders(Arrays.asList(
                    "Authorization",
                    "Content-Type",
                    "Content-Length",
                    "Upload-Length",
                    "Upload-Offset",
                    "Upload-Metadata",
                    "Tus-Resumable",
                    "X-Requested-With",
                    "X-HTTP-Method-Override"
            ));
            // TUS protocol requires these headers to be exposed to JavaScript
            apiConfig.setExposedHeaders(Arrays.asList(
                    "Location",
                    "Tus-Resumable",
                    "Tus-Version",
                    "Tus-Extension",
                    "Tus-Max-Size",
                    "Upload-Offset",
                    "Upload-Length",
                    "Upload-Metadata"
            ));
            apiConfig.setAllowCredentials(true);
            apiConfig.setMaxAge(3600L); // Cache preflight for 1 hour

            source.registerCorsConfiguration(API_PREFIX + "/**", apiConfig);

            // GraphQL API CORS configuration
            CorsConfiguration graphqlConfig = new CorsConfiguration();
            graphqlConfig.setAllowedOrigins(Arrays.asList(allowedOrigins));
            graphqlConfig.setAllowedMethods(Arrays.asList("POST", "OPTIONS"));
            graphqlConfig.setAllowedHeaders(List.of("*"));
            graphqlConfig.setAllowCredentials(true);

            source.registerCorsConfiguration(graphQlBaseUrl + "/**", graphqlConfig);
        }

        return source;
    }
}
